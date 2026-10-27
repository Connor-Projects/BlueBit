# CoAP Read-Only Probe — Implementation Notes

## Summary

Phase 4 adds a manually triggered diagnostic, **Probe CoAP Read-Only**, to BlueBit. The probe reuses the proven BLE + Gattlink + DTLS bootstrap sequence from `DtlsBootstrapProbe` and then sends exactly one CoAP `GET /.well-known/core` request over the established DTLS session.

## Files

### Created / in place

- `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt`
  - Self-contained probe class.
  - Exposes `suspend fun runProbe(device: BluetoothDevice): CoapReadOnlyResult`.
  - Establishes BLE, Gattlink, and GoldenGate DTLS using the same sequence as `DtlsBootstrapProbe`.
  - Attaches a `CoapEndpoint`, waits for DTLS `TLS_STATE_SESSION` (state == 2) up to 20 s, then sends one `GET /.well-known/core` with a 10-second `withTimeoutOrNull`.
  - Logs response code, response length, and a truncated resource listing (max ~240 chars) under tag `BlueBitCoAP_RO`.
  - Does not log PSKs, tokens, keys, or other secret material.
  - Cleans up CoapEndpoint, stack, bridge, and GATT on completion or failure.

- `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt`
  - Hardware-independent helpers used by the probe and by desktop tests.
  - Defines `CoapReadOnlyResult`, `CoapRequestSpec`, `CoapResponseCode`, `CoapReadOnlyResponseSummary`.
  - Provides request construction, response processing, secret redaction, link-format parsing/truncation, timeout-result creation, and cleanup-action runner.

- `BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeTest.kt`
  - 10 desktop tests covering request construction, response handling, truncation, secret redaction, empty/error responses, malformed link-format, timeout, and cleanup failure handling.

### Modified

- `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt`
  - Fixed secret redaction regex so it stops at `&` and `;` separators (`[^\s,>&;]*`). This keeps multiple secret query parameters separate instead of consuming the whole query string.

- `BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeTest.kt`
  - Corrected expected `responseLength` for the sample `</sensor/temp>,</sensor/hum>` body from 30 to 28 to match the actual test payload length.

- `BlueBitApp/app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/Stack.kt`
  - Added a DTLS status listener hook (`setDtlsStatusListener`) so `CoapReadOnlyProbe` can observe DTLS state transitions.
  - Redacted PSK identity logging in `onDtlsStatusChange` as a security fix; the method now logs `pskIdentity=<redacted>` instead of the raw identity bytes.

### Verified unchanged

- `BlueBitApp/app/src/androidMain/kotlin/bluebit/MainActivity.kt`
  - Already contains `coapReadOnlyProbe = CoapReadOnlyProbe(this)` and the **Probe CoAP Read-Only** `ProviderDiagnostic` button wired into `lifecycleScope.launch`.

- `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt`
  - Not modified.
  - Note: still logs the PSK identity from its own `setDtlsStatusListener` callback; this was intentionally left unchanged per instructions.

## CoAP API Verification

The following APIs were confirmed from the existing Fitbit GoldenGate sources:

- `com.fitbit.goldengate.bindings.coap.CoapEndpoint`
  - `suspend fun sendRequest(request: OutgoingRequest): IncomingResponse` — confirmed.
- `com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder`
  - Constructor `OutgoingRequestBuilder(path: String, method: Method)` exists and splits the path on `/`, adding each non-empty segment as a `UriPathOption` — confirmed.
- `com.fitbit.goldengate.bindings.coap.data.Method`
  - `Method.GET` exists — confirmed.

## Build Results

All requested tasks were run from `G:\Dev\BlueBit\BlueBitApp` using `cmd /c gradlew.bat <task>`.

| Task | Result | Notes |
|------|--------|-------|
| `:app:compileDebugKotlinAndroid` | ✅ SUCCESS | 15 actionable tasks: 15 up-to-date. |
| `:app:compileKotlinDesktop` | ✅ SUCCESS | 2 actionable tasks: 2 up-to-date. |
| `:app:desktopTest` | ✅ SUCCESS | 6 actionable tasks: 6 up-to-date; 67 tests completed, 0 failures, 0 errors. |
| `:app:assembleDebug` | ✅ SUCCESS | 39 actionable tasks: 3 executed, 36 up-to-date; debug APK produced. |

### Debug APK

- Path: `app/build/outputs/apk/debug/app-debug.apk`
- Size: **27,295,828 bytes** (~27 MB)

## How to Run the Diagnostic

1. Build and install the debug APK (e.g., via `./gradlew installDebug` or manually).
2. In the BlueBit app, scan for / select a Fitbit device.
3. Open the device’s diagnostic actions.
4. Tap **Probe CoAP Read-Only**.
5. The probe will connect over BLE, set up Gattlink + DTLS, send one `GET /.well-known/core`, and log the result under `BlueBitCoAP_RO`.

No CoAP request is sent automatically; it only fires when the button is tapped. The probe targets only `/.well-known/core` and does not access `/alarms`, `/device/info`, `/pair/status`, `/bond/control`, or any other path.

## Constraints Observed

- Diagnostic is manually triggered only.
- Only `/.well-known/core` is requested.
- `DtlsBootstrapProbe.kt` behavior is unchanged.
- Fitbit/FitCloud providers are unchanged.
- No secret key material is logged by the CoAP read-only probe or by `Stack.kt` (`pskIdentity` is redacted). `DtlsBootstrapProbe.kt` behavior is unchanged and continues to log PSK identity as noted above.

## Diagnostic Improvement — Configurable Path and Result Capture

After `GET /.well-known/core` returned **4.04 Not Found** on an Inspire 3, no other reference read-only path had enough source evidence to be safe and confirmed on Inspire 3. The probe was therefore improved as a conservative diagnostic change that adds **no new watch traffic** by itself:

1. **Configurable probe path** — `CoapReadOnlyProbeLogic.createRequestSpec(path)` and `CoapReadOnlyProbe.runProbe(device, path)` now accept an optional path argument. It defaults to `.well-known/core`, so the existing button behaviour is unchanged. A leading `/` is stripped, blank paths are rejected, and query strings are rejected.
2. **Last result captured in UI** — `MainActivity` holds `lastCoapReadOnlyResult` and updates it when the diagnostic finishes. `DeviceDetailScreen` displays a compact read-only card showing `success`, `dtlsEstablished`, `coapSuccess`, `responseCode`, `responseLength`, `resourceSummary`, and `error`. This preserves the 4.04 result beyond logcat.
3. **No unverified-path probing added** — BlueBit still does not automatically probe, and there is no UI selector for, `/device/info`, `/alarms`, `/pair/*`, `/bond/control`, or any other unverified path. Only the existing **Probe CoAP Read-Only** button is present, and it still sends `GET /.well-known/core` by default.
