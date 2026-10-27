# Alta HR Investigation Report

## Section 1: Scope and Safety Constraints

- The Alta HR is a family member's device; we did NOT scan, connect, pair, or interact with it physically
- All findings are from APK static analysis, prior research, and code infrastructure preparation
- The code changes enable FUTURE safe observation without altering the device's state

## Section 2: What Was Actually Done (Observed Facts)

List these code changes made to BlueBit to prepare for safe Alta HR observation:

1. **Added manufacturer data and raw scan bytes capture** (`BleScanResult.kt`)
   - New field `manufacturerData: Map<Int, ByteArray>` — captures `ScanRecord.manufacturerSpecificData`
   - New field `rawBytes: ByteArray?` — captures complete raw `ScanRecord.bytes`
   - Both have default values; backward-compatible

2. **Enhanced Android scanner logging** (`AndroidBleScanner.kt`)
   - Extracts manufacturer data from `ScanRecord.manufacturerSpecificData` (SparseArray → Map)
   - Extracts raw advertisement bytes
   - Logs manufacturer data IDs for every scan result
   - Existing Fitbit UUID filters unchanged

3. **Added unfiltered BLE scan mode** (`BleScanner.kt`, `AndroidBleScanner.kt`)
   - New interface method `startUnfilteredScan()` — no UUID filters, discovers ALL BLE devices
   - `NoOpBleScanner` implements the new method
   - Empty filter list passed to `BluetoothLeScanner.startScan()`
   - Existing filtered scan (`startScan()`) unchanged and remains default

4. **Added unfiltered scan UI** (`DevicesScreen.kt`, `BlueBitAppShell.kt`)
   - Secondary button "Scan All BLE Devices" below existing "Scan for Devices"
   - Wires to `startUnfilteredScan()` / `stopScan()`
   - Existing UI behavior unchanged

5. **Added manufacturer data display** (`UiDevice` in `Screen.kt`, `BlueBitAppShell.kt`)
   - `manufacturerDataSummary` field in `UiDevice`
   - Displays as "0xABCD=12b" format in device list

6. **Generalized GATT diagnostic header** (`FitbitGattDiagnostic.kt`)
   - Changed "INSPIRE 3 GATT DATABASE" → "DEVICE GATT DATABASE"
   - Same read-only behavior; works for any BLE device

## Section 3: Alta HR Protocol Architecture (from APK Analysis and Research)

**Observed Facts:**
- Zero references to "Alta" or "Alta HR" found in decompiled Fitbit-base.apk (modern app)
- The Alta HR was released in 2017, before GoldenGate was introduced
- The modern APK retains legacy protocol code: Megadump (187 refs), Microdump (15 refs), XTEA (16 refs)
- The APK scan filters include legacy device names: "One", "Flex", "Charge", "Charge HR", "Force" — but NOT "Alta"
- The `hasMegadumpFwUpdate` flag exists in device type models, suggesting backward compatibility

**Inferences (clearly marked):**
- [INFERENCE] The Alta HR almost certainly uses the pre-GoldenGate protocol (direct BLE, MEGA_DUMP/microdump sync, XTEA encryption)
- [INFERENCE] The Alta HR does NOT use Gattlink, GoldenGate, DTLS, or CoAP
- [INFERENCE] The Alta HR likely advertises with standard BLE services (180A Device Information) and possibly a Fitbit proprietary service, but NOT the modern ABBAFB00/ABBAFF00/FD62/FD63 services
- [INFERENCE] The Alta HR would be discovered by the unfiltered scan, but likely NOT by the Fitbit-filtered scan (no matching UUID)

## Section 4: Inspire 3 BLE Architecture (from Project Code)

**Observed Facts:**
- Inspire 3 uses these advertised service UUIDs (from APK scan filters):
  - `ADABFB00-6E7D-4601-BDA2-BFFAA68956BA` (Fitbit legacy?)
  - `0000FD63-0000-1000-8000-00805F9B34FB` (modern Fitbit)
  - `ABBAFB00-E56A-484C-B832-8B17CF6CBFE8` (Fitbit)
  - `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` (Gattlink transport)
  - `0000FD62-0000-1000-8000-00805F9B34FB` (Fitbit Gattlink)
  - `0000181D-0000-1000-8000-00805F9B34FB` (Weight Scale — generic SIG)
- GATT services after connection:
  - `ABBAFF00` (Gattlink: ABBAFF01 receive, ABBAFF02 transmit)
  - `ABBAFC00` (Link Configuration: ABBAFC01-FC03)
  - `AC2F0045` (Gatt Cache: AC2F0145 ephemeral pointer)
  - `00001801` (Generic Attribute — standard SIG)
- Protocol stack: BLE → Gattlink → GoldenGate → DTLS (PSK) → CoAP
- DTLS identity format: `MD-XXXXXXXX-00000000`
- Cipher suite: `TLS_PSK_WITH_AES_128_CCM` (0xc0a4)

## Section 5: Comparison Table

| Aspect | Alta HR (inferred) | Inspire 3 (confirmed) |
|--------|-------------------|----------------------|
| Release year | 2017 | 2022 |
| Protocol generation | Pre-GoldenGate | GoldenGate |
| BLE transport | Direct notifications | Gattlink framing |
| Encryption | [INFERENCE] XTEA | DTLS + AES-128-CCM |
| Sync method | [INFERENCE] MEGA_DUMP/microdump | CoAP resources |
| Advertised UUIDs | [INFERENCE] 180A + possibly proprietary | ABBAFB00, ABBAFF00, FD62, FD63, 181D |
| Gattlink services | [INFERENCE] None | ABBAFF00, ABBAFC00, AC2F0045 |
| Pairing | [INFERENCE] Legacy (name-based or PIN) | DTLS PSK + server-mediated |
| Server dependency | [INFERENCE] Sync requires Fitbit cloud | Onboarding requires Fitbit cloud |

## Section 6: Does BlueBit Assume All Fitbits Use Modern Architecture?

**Answer: Yes, partially.**

**Evidence:**
- `FitbitGattUuids.kt` only defines GoldenGate-era UUIDs (ABBA*, FD62, FD63)
- `AndroidBleScanner.kt` only filters for GoldenGate-era service UUIDs
- `DeviceProfileRepository.kt` has `emptyList()` default profiles — no Alta HR profile
- The `BleServiceData.parse()` expects exactly 8 bytes (modern Inspire 3 format)
- `BlueBitDeviceMatcher.matchByServiceData()` only checks `0000180A` service data

**Assessment:** BlueBit does not explicitly assume ALL Fitbits use GoldenGate, but its current implementation is optimized exclusively for the Inspire 3 / modern Fitbit architecture. The unfiltered scan mode now provides a path to discover older devices.

## Section 7: Architectural Adjustment Needed?

**Minimal adjustment required:**

1. **No changes to Inspire 3 code** — The existing implementation is correct for its target device.
2. **The unfiltered scan is sufficient for discovery** — Older devices like Alta HR will appear in the unfiltered scan list.
3. **GATT diagnostic is now device-agnostic** — The header change makes it clear the diagnostic works for any device.
4. **Manufacturer data capture enables protocol identification** — If an Alta HR advertises manufacturer data, it can now be logged and analyzed.
5. **Future work (if Alta HR is ever observed):**
   - Add Alta HR-specific `DeviceProfile` to `defaultProfiles` (only after observing actual identifier bytes)
   - Potentially add legacy protocol parsing (MEGA_DUMP) if that becomes a goal
   - Do NOT add unless actual device data is observed

## Section 8: Files Changed

List exactly these files with one-line description:
1. `app/src/commonMain/kotlin/bluebit/bluetooth/BleScanResult.kt` — Added manufacturerData and rawBytes fields
2. `app/src/commonMain/kotlin/bluebit/bluetooth/BleScanner.kt` — Added startUnfilteredScan() interface method
3. `app/src/androidMain/kotlin/bluebit/bluetooth/android/AndroidBleScanner.kt` — Extracts manufacturer data, raw bytes; added unfiltered scan
4. `app/src/commonMain/kotlin/bluebit/ui/screens/DevicesScreen.kt` — Added "Scan All BLE Devices" button
5. `app/src/commonMain/kotlin/bluebit/ui/screens/Screen.kt` — Added manufacturerDataSummary to UiDevice
6. `app/src/commonMain/kotlin/bluebit/BlueBitAppShell.kt` — Wired unfiltered scan and manufacturer data display
7. `app/src/androidMain/kotlin/bluebit/bluetooth/android/FitbitGattDiagnostic.kt` — Generalized header text
8. `gradle.properties` — Added JVM heap settings (org.gradle.jvmargs=-Xmx2048m)

## Section 9: Build and Test Results

```
:app:compileDebugKotlinAndroid — BUILD SUCCESSFUL (14 tasks, all up-to-date)
:app:desktopTest — BUILD SUCCESSFUL (6 tasks, 3 executed, tests passed)
:app:assembleDebug — BUILD SUCCESSFUL (37 tasks, 16 executed, APK generated)
```

- No compilation errors in changed files
- No test failures
- One pre-existing deprecation warning in BottomNavigation.kt (unrelated)
- Native library `libxp.so` packaging note (pre-existing)

## Section 10: How to Observe the Alta HR (Future Steps)

Since the Alta HR was NOT physically interacted with, these steps remain for future execution:

1. Install the debug APK on an Android device with BLE
2. Open BlueBit, go to Devices screen
3. Tap "Scan All BLE Devices" (unfiltered mode)
4. Look for device name "Alta HR" or similar in the list
5. Note: address, RSSI, manufacturer data summary
6. Tap the device card to view details
7. If GATT inspection is available, use the diagnostic to connect and discover services
8. Record ALL logcat output from `BlueBitScan` and `BlueBitGatt` tags
9. Do NOT tap "Pair" — this is for Inspire 3 only
10. Disconnect after observation; do NOT write anything to the device

## Section 11: Summary

- BlueBit now has infrastructure to safely observe older Fitbit devices
- The Alta HR almost certainly uses a different protocol stack than Inspire 3
- No existing Inspire 3 code was weakened or removed
- The unfiltered scan and manufacturer data capture are the key additions
- Physical observation of the Alta HR was intentionally deferred to respect device ownership
