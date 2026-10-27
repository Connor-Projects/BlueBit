# Inspire 3 Gattlink Startup Investigation

**Date:** 2026-10-02  
**Scope:** Verify whether the Inspire 3 can start Gattlink via `AC2F0145 → ephemeral write → 0x00`.  
**Status:** Sequence ALREADY implemented in BlueBit; needs focused diagnostic harness with notification listening.

---

## Current BlueBit Sequence

### Inspire3PairingHandler.kt
- Connects GATT, discovers services.
- Reads Device Name (0x2A00).
- Reads ephemeral pointer `AC2F0145` under `AC2F0045` service — **but only to "trigger auto-pairing"**.
- Does **NOT** write `0x00` to the parsed ephemeral UUID.
- Waits for `ACTION_BOND_STATE_CHANGED` → bonded.
- Falls back to `device.createBond()` if bonding doesn't happen.
- **Closes GATT connection** after bonding attempt.
- **Problem:** This approach expects Android SMP pairing to happen. Community evidence (philldk, Gadgetbridge #504) confirms `createBond()` is unnecessary and Gattlink starts after cache validation, not bonding.

### FitbitGattCacheDiagnostic.kt
- **COMPLETE IMPLEMENTATION** of the cache validation sequence.
- Steps:
  1. Connects GATT to device.
  2. Discovers services.
  3. Finds `AC2F0045` service.
  4. Reads `AC2F0145` characteristic.
  5. Parses 16-byte response as UUID (big-endian: bytes 0-7 = MSB, bytes 8-15 = LSB).
  6. Writes `0x00` with `WRITE_NO_RESPONSE` to the parsed UUID under `AC2F0045`.
  7. Logs everything.
  8. **Immediately disconnects** after write completes.
- **Missing:** Does NOT listen for Gattlink notifications after the write.

### FitbitGoldenGateDiagnostic.kt
- Also implements the cache validation sequence in `proceedToGattDatabaseValidation()`.
- Reads `AC2F0145`.
- Parses UUID using explicit big-endian shift (same as GattCacheDiagnostic).
- Writes `0x00` to ephemeral characteristic.
- On write success, proceeds to `enableGattlinkNotifications(gatt)` → subscribes to CCC on `ABBAFF02`.
- Then starts the full GoldenGate native stack (Stack, DTLS, CoAP).
- **This is the closest to what we want**, but it's bundled inside the massive GoldenGate experiment.

---

## Expected/Test Sequence (from APK + community evidence)

```
1. BLE_CONNECT(device)
2. GATT_DISCOVER_SERVICES
3. FIND_SERVICE(AC2F0045)
4. READ_CHARACTERISTIC(AC2F0145)
   → Response: 16 bytes representing ephemeral write characteristic UUID
5. PARSE_UUID(response, big-endian)
6. WRITE_CHARACTERISTIC(parsed_UUID, [0x00], WRITE_NO_RESPONSE)
   → Under AC2F0045 service
7. WAIT/NOTIFY for Gattlink traffic (ABBAFF02 notifications)
   → Expected: RESET_REQUEST (0x80 ...), RESET_COMPLETE (0x81 ...)
   → Window size 8 in RESET_COMPLETE matters
8. BLE_DISCONNECT
```

Evidence source: philldk comment on Gadgetbridge #504 (2026-09-22):
> "read ac2f0145 to get the UUID of the current 'ephemeral write' characteristic (it rotates per session), then write 0x00 into that UUID ('my cache is invalid, send full DB'). The tracker immediately starts Gattlink unprompted (80 RESET_REQUEST, 81 00 00 08 08 RESET_COMPLETE)."

---

## Evidence for AC2F0145

### Direct evidence (CONFIRMED)

1. **BlueBit Inspire3PairingHandler.kt:36-37**
   ```kotlin
   val GATTDB_CONFIRM_SERVICE = java.util.UUID.fromString("ac2f0045-8182-4be5-91e0-2992e6b40ebb")
   val EPHEMERAL_POINTER_CHAR = java.util.UUID.fromString("ac2f0145-8182-4be5-91e0-2992e6b40ebb")
   ```

2. **BlueBit FitbitGattCacheDiagnostic.kt:28-29**
   ```kotlin
   private val GATT_CACHE_SERVICE_UUID = UUID.fromString("AC2F0045-8182-4BE5-91E0-2992E6B40EBB")
   private val EPHEMERAL_POINTER_UUID = UUID.fromString("AC2F0145-8182-4BE5-91E0-2992E6B40EBB")
   ```
   Comment: "Based on APK analysis of GattDatabaseValidator.java."

3. **BlueBit FitbitGoldenGateDiagnostic.kt:56-57**
   ```kotlin
   private val GATTDB_CONFIRM_SERVICE = UUID.fromString("AC2F0045-8182-4BE5-91E0-2992E6B40EBB")
   private val EPHEMERAL_POINTER_CHAR = UUID.fromString("AC2F0145-8182-4BE5-91E0-2992E6B40EBB")
   ```

4. **AltaHR_Investigation_Report.md Section 4**
   > "AC2F0045 (Gatt Cache: AC2F0145 ephemeral pointer)"

5. **Fitbit_Whole_APK_Onboarding_Audit.md Section 4.2**
   > "AC2F0045 — Gatt Cache service (AC2F0145 ephemeral pointer)"

### APK evidence (CONFIRMED)

From the decompiled Fitbit APK, the `FitbitGattCacheDiagnostic` comment references:
> "Based on APK analysis of GattDatabaseValidator.java."

The APK contains GattDatabaseValidator logic that reads the ephemeral pointer and writes 0x00 to validate the cache.

---

## Evidence for Ephemeral Write Characteristic

### How the UUID is derived

When `AC2F0145` is read, it returns 16 bytes. These bytes are parsed as a UUID:

**FitbitGattCacheDiagnostic.kt:117-121**
```kotlin
val parsedUuid = try {
    val bb = ByteBuffer.wrap(value)
    UUID(bb.long, bb.long)
} catch (e: Exception) { ... }
```

**FitbitGoldenGateDiagnostic.kt:323-335**
```kotlin
for (i in 0 until 8) {
    msb = (msb shl 8) or (value[i].toLong() and 0xFF)
}
for (i in 8 until 16) {
    lsb = (lsb shl 8) or (value[i].toLong() and 0xFF)
}
UUID(msb, lsb)
```

Both methods produce the same result: **big-endian** interpretation of bytes as `long` MSB + `long` LSB.

### Write operation

**FitbitGattCacheDiagnostic.kt:172-196**
```kotlin
private fun performWrite(gatt: BluetoothGatt, targetUuid: UUID) {
    val service = gattCacheService
    val targetChar = service?.getCharacteristic(targetUuid)
    ...
    targetChar.value = byteArrayOf(0x00)
    targetChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    val writeResult = gatt.writeCharacteristic(targetChar)
}
```

Writes `0x00` with `WRITE_TYPE_NO_RESPONSE`.

---

## Evidence Concerning Android createBond()

### Official APK evidence
The official app calls `bond/control` CoAP FIRST, then `createBond()` SECOND. BlueBit's `Inspire3PairingHandler` does the opposite (or tries bonding without `bond/control`).

### Community evidence (STRONG)
philldk (Gadgetbridge #504, 2026-09-22):
> "confirmed 0 SMP events, 0 encryption events in a full sync capture — createBond() always fails and isn't needed. The tracker won't start Gattlink until you confirm its GATT cache."

**Conclusion:** `createBond()` is NOT required for Gattlink startup. The cache validation sequence (`read AC2F0145 → write 0x00`) is sufficient.

---

## What is Confirmed

| Item | Status | Evidence |
|------|--------|----------|
| Service UUID (`AC2F0045`) | **CONFIRMED** | 3 files in BlueBit + APK + research reports |
| Ephemeral pointer UUID (`AC2F0145`) | **CONFIRMED** | 3 files in BlueBit + APK + research reports |
| Read AC2F0145 returns 16 bytes | **CONFIRMED** | FitbitGattCacheDiagnostic.kt, FitbitGoldenGateDiagnostic.kt |
| Parse as UUID (big-endian) | **CONFIRMED** | Both diagnostics implement same parsing logic |
| Write `0x00` to parsed UUID | **CONFIRMED** | FitbitGattCacheDiagnostic.kt implements this |
| Write uses `WRITE_NO_RESPONSE` | **CONFIRMED** | FitbitGattCacheDiagnostic.kt:190 |
| Gattlink starts after write | **INFERRED** (strong) | philldk comment, no direct BlueBit observation |
| `createBond()` not needed | **INFERRED** (strong) | philldk comment, 0 SMP events observed |
| Gattlink traffic format | **INFERRED** | philldk: RESET_REQUEST (0x80), RESET_COMPLETE (0x81) |
| Window size 8 matters | **INFERRED** | philldk comment |

## What Remains Unknown

| Item | Why Unknown |
|------|-------------|
| Exact Gattlink PDU format after startup | BlueBit has not observed live Gattlink traffic from Inspire 3 |
| Whether RESET_REQUEST/RESET_COMPLETE appear on ABBAFF02 | philldk observed this on Fitbit Air; Inspire 3 may differ |
| Whether DTLS can proceed without prior bonding | Only tested with BondCommandsHandler flow in official app |
| What happens if no official app onboarding was done | `bond/control` may still return 4.01 even with Gattlink started |
| Ephemeral UUID rotation behavior | Does it change every session? Every reconnect? |

---

## Recommended Diagnostic Test

A focused diagnostic should:
1. Connect GATT to Inspire 3.
2. Discover services.
3. Read `AC2F0145`.
4. Parse 16-byte response as UUID (big-endian).
5. Write `0x00` to parsed UUID under `AC2F0045`.
6. Subscribe to `ABBAFF02` (Gattlink transmit characteristic) notifications.
7. Listen for ~10 seconds.
8. Log all Gattlink PDUs via `BleLogWatchdog` and Android `Log`.
9. Disconnect cleanly.

This diagnostic already exists in fragmented form across `FitbitGattCacheDiagnostic` (steps 1-5) and `FitbitGoldenGateDiagnostic` (steps 6-9). A unified focused diagnostic is needed.
