# BlueBit Live DTLS Bootstrap Pre-flight Analysis

## Scope & Method

This report assesses whether the BlueBit Android app (`G:/Dev/BlueBit/BlueBitApp`) is safe and ready to run a **live DTLS bootstrap / CoAP read-only smoke test** against an already-paired Fitbit Inspire 3.  
The assessment is based entirely on source tracing and existing project documentation. **No source files were modified, the tracker was not contacted, and the app was not installed or run.**

Key files examined:

- `app/src/androidMain/kotlin/bluebit/MainActivity.kt`
- `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt`
- `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt`
- `app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt`
- `app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/DtlsProbeStateMachine.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/GoldenGate.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/Bridge.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/RunLoop.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/Stack.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/DtlsSocketNetifGattlinkStackConfig.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolver.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolverRegistry.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolver.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/HelloTlsKeyResolver.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpoint.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/SingleCoapResponseListener.kt`
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OutgoingRequestBuilder.kt`
- `app/src/androidMain/kotlin/bluebit/core/bluetooth/android/AndroidBleScanner.kt`
- `app/src/androidMain/AndroidManifest.xml`
- `app/build.gradle.kts`
- Project analysis notes in `prototype_analysis/`

---

## 1. Call Path: MainActivity → CoAP Response

The user-facing entry point is created in `MainActivity.onCreate`:

```kotlin
// MainActivity.kt
val coapReadOnlyProbe = CoapReadOnlyProbe(this)
fitbitProvider = FitbitProvider(
    context = this,
    ...
    diagnosticsFactory = { device ->
        listOf(
            ProviderDiagnostic(
                id = "coap_readonly_probe",
                label = "Probe CoAP Read-Only",
                action = { address ->
                    val device = bluetoothAdapter.getRemoteDevice(address)
                    lifecycleScope.launch {
                        val result = coapReadOnlyProbe.runProbe(device)
                        lastCoapReadOnlyResult.value = result
                    }
                }
            ),
            ...
        )
    }
)
```

`DeviceDetailScreen` renders these diagnostics as secondary buttons. Tapping **“Probe CoAP Read-Only”** invokes the action, which launches `coapReadOnlyProbe.runProbe(device)`.

Inside `CoapReadOnlyProbe.runProbe` (and the very similar `DtlsBootstrapProbe.runProbe`):

1. `GoldenGate.init()` loads `libxp.so` and starts the native run-loop.
2. A `Bridge` is created with a native `TxSink` / `RxSource` pair.
3. `BluetoothGatt` is opened via `connectGatt(..., BluetoothDevice.TRANSPORT_LE)`.
4. The GATT callback requests MTU `185`, discovers services, and validates the Golden Gate service:
   - Service `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8`
   - Receive/write characteristic `ABBAFF01-...`
   - Transmit/notify characteristic `ABBAFF02-...`
5. Service-Changed indications are enabled, the GattDatabase ephemeral pointer is read/written (`AC2F0045` / `AC2F0145`), and notifications are enabled on `ABBAFF02`.
6. `startStack()` builds:
   - `DtlsSocketNetifGattlinkStackConfig` (descriptor `DSNG`, local/remote `0.0.0.0:0`)
   - `Stack(nodeKey = BluetoothAddressNodeKey(device.address), transportSink, transportSource, config, isNode = false)`
   - `CoapEndpoint().attach(stack)`
   - `bridge.start()` and `stack.start()`
   - `stack.updateMtu(negotiatedMtu - 3)`
7. A DTLS status listener is installed; it delegates to `DtlsProbeStateMachine.onDtlsStatusChange(state, code)`.
8. Once the state machine reports success (DTLS state `2`), `sendCoapRequest()` runs:
   - `OutgoingRequestBuilder(".well-known/core", Method.GET).build()`
   - `coapEndpoint.sendRequest(request)` using `SingleCoapResponseListener` on `Dispatchers.IO`
   - The response code/body are parsed into `CoapReadOnlyResult`.

`DtlsBootstrapProbe` follows the same setup path but stops after DTLS session establishment and logs the result; it does **not** send a CoAP request by default.

---

## 2. Native Loading & Thread Requirements

- **`libxp.so`** is the only native artifact present. It lives at:
  `app/src/androidMain/jniLibs/arm64-v8a/libxp.so`
- It is loaded at class-initialization time in `GoldenGate.kt`:
  ```kotlin
  init { System.loadLibrary("xp") }
  ```
- `GoldenGate.init()` must be called before creating native objects; it calls `check()` and then:
  - `initModulesJNI()`
  - `registerLoggerJNI()`
  - `RunLoop.startAndWaitUntilReady()` — blocks the **calling thread** until the native run-loop invokes `onLoopCreated()` via JNI.
- The native stack, DTLS protocol, and Gattlink logic all run on the native run-loop thread.
- BLE callbacks arrive on Android binder threads; `Bridge.receiveFromBle()` pushes bytes into `RxSource.receiveData()`.
- Outbound bytes are emitted through `TxSink.putData()` (native callback on run-loop thread) → `Bridge.onTxData()` → `BluetoothGatt.writeCharacteristic()`.

### Important threading notes
- `GoldenGate.init()` is called from the main/UI coroutine in `runProbe`. It blocks the main thread briefly. If the native run-loop is slow to start, this could trigger an ANR.
- `BluetoothGatt.writeCharacteristic()` is invoked from the native run-loop thread. Android BLE requires writes to be serialized one-at-a-time; there is no explicit write-queue in the probe, so rapid back-to-back native transmits risk `GATT_BUSY` or dropped frames.

---

## 3. MobileData Fallback Behavior

### Resolver chain
`TlsKeyResolverRegistry` builds a chain at object init:

```kotlin
private val firstResolver: HelloTlsKeyResolver = HelloTlsKeyResolver()
private var lastResolver: TlsKeyResolver = firstResolver
init { register(BootstrapTlsKeyResolver()) }
```

Resulting order: `HelloTlsKeyResolver` → `BootstrapTlsKeyResolver`.

- `HelloTlsKeyResolver` matches identity `"hello"`.
- `BootstrapTlsKeyResolver` matches identity `"BOOTSTRAP"` and returns the 16-byte static bootstrap key.
- **There is no resolver for MobileData identities (`MD-...`).**

### What happens on a MobileData identity
1. The Inspire 3 first presents a MobileData PSK identity.
2. The chain returns `null`.
3. Native maps this to `GG_ERROR_TLS_UNKNOWN_IDENTITY` (`-10601`).
4. The native DTLS server emits `state = 3` (`TLS_STATE_ERROR`) with `code = -10601`.
5. **In server mode the native stack auto-resets**: it returns to `state = 0` (`TLS_STATE_INIT`) and immediately re-enters handshake.
6. The tracker then retries with the `BOOTSTRAP` identity; the resolver returns the static key and the handshake completes (`state = 2`).

### Current mismatch — this is a live-test blocker
`DtlsProbeStateMachine.onDtlsStatusChange` interprets **any** `state == 3` as a terminal error and completes `dtlsSessionDeferred` with `false`:

```kotlin
DtlsStatusInterpretation.ERROR -> {
    dtlsSessionDeferred.complete(false)
}
```

Because the native server retries automatically, the probe **should** ignore transient `TLS_STATE_ERROR` and wait only for `state == 2`. The project investigation notes explicitly cite the reference implementation (`StackPeer.kt`), which only reacts to `TLS_STATE_SESSION` because “there are retries built into the XP library.”

**Conclusion:** As written, both `DtlsBootstrapProbe` and `CoapReadOnlyProbe` will abort during the MobileData → BOOTSTRAP fallback and will not reach a successful DTLS session.

---

## 4. Permission Status

### Manifest (`AndroidManifest.xml`)
Declared permissions include:

- `BLUETOOTH`
- `BLUETOOTH_ADMIN` (`tools:node="replace"`)
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION` (`android:maxSdkVersion="23"`)
- `INTERNET`

### Runtime request (`MainActivity.requestBlePermissions()`)
Requests:

- `ACCESS_FINE_LOCATION`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `POST_NOTIFICATIONS` on API 33+

`AndroidBleScanner.hasScanPermission()` correctly checks `BLUETOOTH_SCAN` on API 31+ and `ACCESS_FINE_LOCATION` on older devices.

### Gaps / observations
- `BLUETOOTH_SCAN` does not declare `android:usesPermissionFlags="neverForLocation"`. This is a Google Play policy concern, not a functional blocker for a local test, but it means the system may still treat the scan as location-derived on some Android versions.
- No `BLUETOOTH_ADVERTISE`, `BLUETOOTH_PRIVILEGED`, or background-location permission is requested; those are not needed for this flow.
- `ACCESS_FINE_LOCATION` is only requested up to API 23 in the manifest; on API 24–30 it is still requested at runtime by `requestBlePermissions()`.

### Practical requirement
Before the live test the user must grant **Location**, **Nearby devices/Bluetooth scan**, and **Bluetooth connect** permissions, have Bluetooth enabled, and (on most Android variants) have Location services enabled.

---

## 5. Exact Entry Point for the Read-Only Probe

The read-only CoAP probe is exposed through the diagnostic plumbing as follows:

- **Diagnostic ID:** `coap_readonly_probe`
- **Label shown in UI:** `Probe CoAP Read-Only`
- **Creation site:** `MainActivity.kt`, inside the `diagnosticsFactory` block passed to `FitbitProvider`
- **Action:**
  ```kotlin
  lifecycleScope.launch {
      val result = coapReadOnlyProbe.runProbe(device)
      lastCoapReadOnlyResult.value = result
  }
  ```
- **UI display:** `DeviceDetailScreen` renders the diagnostic list and shows the last result with `CoapReadOnlyResultCard`.
- **Default request:** `GET /.well-known/core`

No separate launcher activity or hidden menu is required; the button appears on the device-detail screen for a selected Fitbit device.

---

## 6. Risk & Blocker List

| # | Risk / Blocker | Severity | Evidence / Notes |
|---|----------------|----------|------------------|
| 1 | **DTLS state machine aborts on MobileData fallback.** `DtlsProbeStateMachine` treats `TLS_STATE_ERROR` (state 3) as fatal, completing the deferred `false` before the native server auto-resets and retries with `BOOTSTRAP`. | **Critical** | `DtlsProbeStateMachine.kt` maps state 3 to `ERROR` and completes `false`. `dtls_error_minus_10601_investigation.md` and `reference_transport_api_report.md` show the reference app ignores state 3 and waits for state 2. The Inspire 3 capture shows the tracker presents a MobileData identity first. |
| 2 | **No known safe read-only path that returns 2.xx on Inspire 3.** The default `/.well-known/core` returned `4.04 Not Found` in a prior test. `/sync/config` returned `4.01 Unauthorized` in the capture. Other reference paths (`/alarms`, `/device/info`, etc.) are documented in the reference app but not verified on the Inspire 3 firmware. | High | `CoAP_ReadOnly_Probe_Implementation_Notes.md`, `reference_coap_readonly_audit.md`, `fitbit_inspire3_traffic_capture_report.md`. A 4.xx response proves end-to-end CoAP reachability but sets `coapSuccess = false`. |
| 3 | **Single ABI native library.** Only `arm64-v8a/libxp.so` is present; no x86 or 32-bit binaries. The app will crash with `UnsatisfiedLinkError` on non-arm64 devices. | High | `app/src/androidMain/jniLibs/arm64-v8a/libxp.so` is the only `libxp.so`. `app/build.gradle.kts` has no `ndk.abiFilters`. |
| 4 | **Main-thread blocking during native init.** `GoldenGate.init()` → `RunLoop.startAndWaitUntilReady()` blocks the UI thread without a timeout. If the native loop stalls, the app ANRs. | Moderate | `GoldenGate.kt` init path; called from `lifecycleScope` launched from the UI in `MainActivity`. |
| 5 | **BLE write concurrency.** `TxSink.putData` calls `BluetoothGatt.writeCharacteristic()` from the native run-loop thread. Android BLE requires serialized writes; there is no queue or busy-check, so rapid transmits may fail with `GATT_BUSY`. | Moderate | `Bridge.kt` / `DtlsBootstrapProbe.kt` write path. |
| 6 | **Stale DTLS listener.** `cleanup()` does not call `stack.setDtlsStatusListener(null)`. A late native callback after close could invoke a completed/cancelled deferred. | Low | `DtlsBootstrapProbe.kt` `cleanup()`. In practice `stack.close()` destroys the native object and should stop callbacks. |
| 7 | **Response-code formatting bug.** The probe logs and result object render `4.04` as `4.4`, which can be misread as a non-standard code. | Low (UX) | `coap_44_response_investigation.md` — confirmed formatting issue in `CoapReadOnlyProbe.kt` and `CoapReadOnlyProbeLogic.kt`. |
| 8 | **MobileData identity logged.** `DtlsBootstrapProbe` logs the raw PSK identity string/hex; if the tracker uses a privacy-sensitive MobileData identity, it appears in logcat. | Low | `DtlsBootstrapProbe.kt` listener logging. |
| 9 | **No instrumentation tests for the live Android path.** Only desktop unit tests exist; they mock BLE and native layers. | Low | `app/src/desktopTest/...` only. |
| 10 | **`0.0.0.0:0` socket endpoints.** `DtlsSocketNetifGattlinkStackConfig` uses all-zero addresses/ports. The live capture uses link-local `169.254.0.2:5684` ↔ `169.254.0.3:5684`, but the reference app also uses `0.0.0.0:0`. This is an uncertainty, not a confirmed failure. | Low | `DtlsSocketNetifGattlinkStackConfig.kt` vs. capture report. |

---

## 7. Recommendation

### Should you proceed with a live test today?

**No — not with the current `DtlsProbeStateMachine`.** The app will almost certainly fail the DTLS handshake against an already-paired Inspire 3 because the probe aborts on the transient MobileData identity error (`state=3, code=-10601`) before the native server can auto-retry with `BOOTSTRAP`.

### Minimum precondition for a safe live test

1. **Fix `DtlsProbeStateMachine`** so that `TLS_STATE_ERROR` (state 3) is treated as non-terminal. The Kotlin layer should only complete successfully on `TLS_STATE_SESSION` (state 2) and should time out otherwise. The reference implementation pattern is:
   ```kotlin
   state == TLS_STATE_SESSION
   ```
   Alternatively, record `lastError` for diagnostics but do **not** complete the deferred on state 3.

2. **Verify the test device is arm64.** The APK will crash on any device that is not `arm64-v8a`.

3. **Grant all required permissions** before launching:
   - Location permission + location services enabled
   - Bluetooth scan / connect permissions
   - Bluetooth enabled

4. **Choose a realistic CoAP path.**
   - If the goal is simply to prove DTLS + CoAP end-to-end reachability, accept any well-formed CoAP response (including `4.01`, `4.04`) as evidence of success.
   - If the goal is a `2.xx` response, the safest documented reference path is **`GET /alarms`** (`AirAlarmProtocol.parseDeviceAlarms`), but it has not been verified on the Inspire 3 firmware in this codebase.
   - Avoid `/pair/display`, `/bond/control`, and any `PUT/POST/DELETE` endpoints unless you are certain they are read-safe.

5. **Reduce main-thread blocking risk.** Consider initializing `GoldenGate` on a background dispatcher (e.g., `lifecycleScope.launch(Dispatchers.Default) { ... }`) rather than the main thread, or add a defensive timeout around the run-loop wait.

6. **Run a transport-only smoke test first.** Use `GattlinkStartupDiagnostic` or `FitbitGattlinkDiagnostic` to confirm BLE services, MTU negotiation, and raw Gattlink frame exchange before attempting DTLS + CoAP.

### Safest minimal live-test sequence (after the state-machine fix)

1. Build and install the debug APK on an arm64 Android device.
2. Grant permissions and enable Bluetooth/Location.
3. Open BlueBit, scan, and select the already-paired Inspire 3.
4. Tap **“Probe Gattlink Startup”** (or equivalent transport diagnostic) and confirm Gattlink `RESET_REQUEST` / `RESET_COMPLETE` and MTU exchange.
5. Tap **“Probe DTLS Bootstrap.”** Expect a sequence like:
   - `state=1 code=0` (handshake)
   - `state=3 code=-10601` (MobileData identity rejected — **ignored by fixed state machine**)
   - `state=0 code=0` (native auto-reset)
   - `state=1 code=0` (re-handshake)
   - `state=2 code=0` (DTLS session established)
6. Tap **“Probe CoAP Read-Only.”** Expect either a `2.xx` (if the chosen resource exists) or a `4.xx` response. Treat any non-timeout CoAP response as proof of end-to-end connectivity.

---

## 8. Bottom Line

- The native stack, BLE plumbing, permission requests, and UI wiring are all present and structurally correct for a live bootstrap test.
- The **only critical blocker** is the Kotlin DTLS state machine’s handling of `TLS_STATE_ERROR`. Until it ignores transient state-3 errors and waits for `TLS_STATE_SESSION`, the probe will fail against a tracker that starts with a MobileData identity.
- Once that is fixed, a live read-only CoAP smoke test against a paired Inspire 3 is reasonable, provided the test device is arm64, permissions are granted, and a safe read path is chosen.
