# DTLS Bootstrap Probe Implementation Notes

## Files Changed

### Created
- `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/DtlsProbeStateMachine.kt`
  - `ProbeStage` enum, `ProbeResult` data class, `DtlsStatusInterpretation` enum, and `DtlsProbeStateMachine` helper.
  - Tracks stage transitions, DTLS status/codes, TX/RX counters, and builds result snapshots.
  - Lives in commonMain so it can be unit-tested on the desktop target.

- `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt`
  - New diagnostic that runs the full BLE→Gattlink→DTLS bootstrap handshake sequence.
  - Uses GoldenGate `Bridge`, `Stack` with `DtlsSocketNetifGattlinkStackConfig`, and `CoapEndpoint` (attached but no requests sent).
  - Stops after DTLS reaches `TLS_STATE_SESSION` (state == 2) or after a 20-second timeout.
  - Cleans up CoapEndpoint, Stack, Bridge, and GATT in all paths.

- `BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbeTest.kt`
  - Unit tests for the extracted state machine: stage ordering/transition rules, DTLS state interpretation, result construction, and timeout constant.

### Modified
- `BlueBitApp/app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/Stack.kt`
  - Added `setDtlsStatusListener(...)` and `dtlsStatusListener` callback.
  - `onDtlsStatusChange` now forwards native DTLS status events to the listener after logging.

- `BlueBitApp/app/src/androidMain/kotlin/bluebit/MainActivity.kt`
  - Added `dtlsBootstrapProbe` instance.
  - Added a new `ProviderDiagnostic` button labeled **"Probe DTLS Bootstrap"**.
  - Button launches `dtlsBootstrapProbe.runProbe(device)` in `lifecycleScope`.

## How to Run the Probe

1. Build and install the debug APK.
2. Open BlueBit and scan/select a Fitbit Inspire 3.
3. In the device details screen, tap **"Probe DTLS Bootstrap"**.
4. Watch logcat with tag `BlueBitDTLSProbe` for stage-by-stage output.

## Expected Stages

The probe logs these stages in order:

1. `NOT_STARTED` → `BLE_CONNECTION` — GATT connect requested.
2. `BLE_CONNECTION` → `BLE_DISCOVERY` — connected, discovering services.
3. `BLE_DISCOVERY` → `GATTLINK_SERVICE_CHECK` — Gattlink service and characteristics verified.
4. `GATTLINK_SERVICE_CHECK` — Service Changed indications enabled, GattDatabase validation read/write performed.
5. `GATTLINK_SERVICE_CHECK` → `GATTLINK_SETUP` — Gattlink TX notifications enabled.
6. `GATTLINK_SETUP` → `DTLS_HANDSHAKE` — GoldenGate Bridge, Stack, and CoapEndpoint created and started.
7. `DTLS_HANDSHAKE` → `SESSION_ESTABLISHED` — DTLS `TLS_STATE_SESSION` (state == 2) observed.
8. `SESSION_ESTABLISHED` → `CLEANUP` — resources released.

If any step fails or DTLS does not reach state 2 within 20 seconds, the probe lands in `FAILED` and then `CLEANUP`.

## Build / Test Results

| Task | Result |
|---|---|
| `:app:compileDebugKotlinAndroid` | SUCCESSFUL |
| `:app:compileKotlinDesktop` | SUCCESSFUL |
| `:app:desktopTest` | 56 tests completed, 0 failed |
| `:app:assembleDebug` | SUCCESSFUL |

APK output:
- Path: `G:\Dev\BlueBit\BlueBitApp\app\build\outputs\apk\debug\app-debug.apk`
- Size: 27,280,579 bytes (~26.02 MB)

## Constraints Observed

- No Golden Gate dependencies were moved into `bluebit.core.*`.
- No Fitbit/FitCloud providers were modified.
- The probe attaches `CoapEndpoint` for stack completeness but does **not** send `bond/control`, `pair/display`, `/settings`, or any other CoAP request.
- Only the public bootstrap PSK already registered in `BootstrapTlsKeyResolver` is used; no per-device keys are extracted.
