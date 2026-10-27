# BlueBit Inspire 3 GattCache Protocol Experiment Report

**Date:** 2026-08-31  
**Device:** Samsung Galaxy A17 (Android 14)  
**Target:** Fitbit Inspire 3 (D8:9E:C1:BA:3D:E4)  
**APK Evidence:** `GattDatabaseValidator.java`, `GattCacheService.java`, `EphemeralCharacteristicPointer.java`

---

## Result Classification

**SUCCESS** — BlueBit reproduced the confirmed Fitbit GattCache operation.

All 3 experimental runs produced identical successful results.

---

## 1. Connection

| Attempt | Connection Status | Service Discovery |
|---------|------------------|-------------------|
| 1 | ✅ status=0 CONNECTED | ✅ status=0, 7 services |
| 2 | ✅ status=0 CONNECTED | ✅ status=0, 7 services |
| 3 | ✅ status=0 CONNECTED | ✅ status=0, 7 services |

---

## 2. GattCache Discovery

### Service `AC2F0045-8182-4BE5-91E0-2992E6B40EBB` — Found ✅

| Property | Value |
|----------|-------|
| UUID | `ac2f0045-8182-4be5-91e0-2992e6b40ebb` |
| Type | PRIMARY (0) |
| Characteristics | 2 |

### Characteristic [0]: `AC2F0145-8182-4BE5-91E0-2992E6B40EBB` — Ephemeral Pointer ✅

| Property | Value |
|----------|-------|
| UUID | `ac2f0145-8182-4be5-91e0-2992e6b40ebb` |
| Properties | READ |
| Permissions | NONE(0x0) |

### Characteristic [1]: `AC2F2645-8182-4BE5-91E0-2992E6B40EBB` — Write Target ✅

| Property | Value |
|----------|-------|
| UUID | `ac2f2645-8182-4be5-91e0-2992e6b40ebb` |
| Properties | WRITE |
| Permissions | NONE(0x0) |

---

## 3. Read Operation

### Execution

```
READ AC2F0045/AC2F0145
```

### Results (all 3 attempts identical)

| Field | Value |
|-------|-------|
| Read initiation | ✅ `result=true` |
| GATT status | ✅ `status=0` (GATT_SUCCESS) |
| Returned length | 16 bytes |
| Returned hex | `AC2F264581824BE591E02992E6B40EBB` |
| Parsed UUID | `ac2f2645-8182-4be5-91e0-2992e6b40ebb` |

### Interpretation

The `AC2F0145` characteristic acts as a **pointer** — it contains the UUID of another characteristic within the same service. In this case, it points to `AC2F2645`.

This matches exactly the APK's `GattDatabaseValidator.readEphemeralPointerCharacteristic()` which calls `UuidHelperKt.toUuid(byte[])` to parse the returned bytes.

**Crucially:** The returned UUID (`AC2F2645`) is the **second characteristic in the service** — the WRITE characteristic. This resolves the APK/Inspire 3 UUID discrepancy from the static analysis:

- APK server-side code defined `EphemeralCharacteristic` as `AC2F0345`
- Inspire 3 firmware actually uses `AC2F2645`
- The pointer (`AC2F0145`) correctly resolves to the actual device characteristic

---

## 4. Write Operation

### Execution

```
WRITE [0x00] with WRITE_NO_RESPONSE
Target: AC2F0045/AC2F2645
```

### Results (all 3 attempts identical)

| Field | Value |
|-------|-------|
| Target UUID | `ac2f2645-8182-4be5-91e0-2992e6b40ebb` |
| Target properties | WRITE |
| Write value | `0x00` (1 byte) |
| Write type | WRITE_NO_RESPONSE (2) |
| Queued result | ✅ `result=true` |
| GATT status | ✅ `status=0` (GATT_SUCCESS) |

### Interpretation

The write of a single zero byte to the resolved characteristic succeeds. This matches the APK's `GattDatabaseValidator.writeEphemeralCharacteristic()`:

```java
.write(GattDatabaseConfirmationService.Companion.getUuid(), uuid, new byte[]{0}, 2)
```

Where:
- Service UUID = `AC2F0045` (GattCacheService)
- Characteristic UUID = parsed from read result (`AC2F2645`)
- Value = `[0x00]`
- Write type = `2` (WRITE_NO_RESPONSE)

---

## 5. APK Correlation

| APK Class | APK Method | BlueBit Operation | Match |
|-----------|------------|-------------------|-------|
| `GattDatabaseValidator` | `readEphemeralPointerCharacteristic()` | `READ AC2F0145` | ✅ Exact |
| `GattDatabaseValidator` | `UuidHelperKt.toUuid(byte[])` | Parse 16 bytes → UUID | ✅ Exact |
| `GattDatabaseValidator` | `writeEphemeralCharacteristic()` | `WRITE [0x00]` to parsed UUID | ✅ Exact |
| `GattCacheService` | UUID = `AC2F0045` | Service discovery | ✅ Exact |
| `EphemeralCharacteristicPointer` | UUID = `AC2F0145` | Read target | ✅ Exact |

---

## 6. Experiment Result

**SUCCESS**

BlueBit successfully reproduced the first confirmed Fitbit post-connection protocol operation:

```text
READ AC2F0145 → parse UUID → WRITE [0x00] to that UUID
```

All 3 runs produced identical results. The Inspire 3:
- Accepts unbonded GATT connections
- Exposes the documented GattCache service
- Returns a valid ephemeral pointer
- Accepts the confirmation write
- Does not require Android BLE bonding

---

## 7. Next Step Recommendation

### Gattlink Transport Experiment

The next smallest verifiable step is to test the **Gattlink service** (`ABBAFF00`):

```text
1. Enable notifications on ABBAFF02 (TransmitCharacteristic)
2. Write Gattlink framing bytes to ABBAFF01 (ReceiveCharacteristic)
3. Observe if the Inspire 3 responds with Gattlink acknowledgements
```

This would verify whether BlueBit can establish the Fitbit transport layer that carries DTLS/CoAP traffic.

**Why this is the right next step:**
- Gattlink is the confirmed transport layer above GattCache
- The APK defines exact UUIDs for the Gattlink service (`ABBAFF00/01/02`)
- No authentication required for basic Gattlink handshake
- Non-destructive (enabling notifications + sending test frames)
- Will reveal if the Inspire 3 expects the Gattlink framing protocol

### Alternative: Characteristic Read Survey

If Gattlink proves too complex, a simpler next step:

```text
READ all characteristics in services [3] and [6]
(abbafd00-... and 4eee1c00-...)
```

This would map the remaining unknown services and potentially reveal their purposes from the returned data.

---

## Log Excerpt (Attempt 1)

```text
BlueBitGattCache: GATTCACHE_EXPERIMENT_START
BlueBitGattCache: DEVICE_NAME=Inspire 3
BlueBitGattCache: DEVICE_ADDRESS=D8:9E:C1:BA:3D:E4
BlueBitGattCache: CONNECTION_STATE status=0 newState=CONNECTED
BlueBitGattCache: SERVICES_DISCOVERED status=0 services=7
BlueBitGattCache: SERVICE_FOUND uuid=ac2f0045-8182-4be5-91e0-2992e6b40ebb type=0
BlueBitGattCache:   Characteristic[0]: uuid=ac2f0145-8182-4be5-91e0-2992e6b40ebb properties=READ
BlueBitGattCache:   Characteristic[1]: uuid=ac2f2645-8182-4be5-91e0-2992e6b40ebb properties=WRITE
BlueBitGattCache: EPHEMERAL_POINTER_FOUND uuid=ac2f0145-8182-4be5-91e0-2992e6b40ebb
BlueBitGattCache: EPHEMERAL_POINTER_PROPERTIES=READ
BlueBitGattCache: READ_REQUESTING uuid=ac2f0145-8182-4be5-91e0-2992e6b40ebb
BlueBitGattCache: READ_REQUESTED result=true
BlueBitGattCache: READ_COMPLETE uuid=ac2f0145-8182-4be5-91e0-2992e6b40ebb status=0
BlueBitGattCache: VALUE_LENGTH=16
BlueBitGattCache: VALUE_HEX=AC2F264581824BE591E02992E6B40EBB
BlueBitGattCache: VALUE_UUID=ac2f2645-8182-4be5-91e0-2992e6b40ebb
BlueBitGattCache: WRITE_TARGET_UUID=ac2f2645-8182-4be5-91e0-2992e6b40ebb
BlueBitGattCache: WRITE_TARGET_PROPERTIES=WRITE
BlueBitGattCache: WRITE_VALUE_HEX=00
BlueBitGattCache: WRITE_TYPE=WRITE_NO_RESPONSE
BlueBitGattCache: WRITE_REQUESTED uuid=ac2f2645-8182-4be5-91e0-2992e6b40ebb
BlueBitGattCache: WRITE_QUEUED result=true
BlueBitGattCache: WRITE_COMPLETE uuid=ac2f2645-8182-4be5-91e0-2992e6b40ebb status=0
BlueBitGattCache: WRITE_SUCCESS
BlueBitGattCache: DISCONNECTED
```
