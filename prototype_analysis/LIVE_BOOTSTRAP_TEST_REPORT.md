# BlueBit Live DTLS Bootstrap Test Report

**Date:** 2026-10-17  
**Project:** `G:/Dev/BlueBit/BlueBitApp`  
**Test:** Live DTLS bootstrap + CoAP `GET /.well-known/core` against an already-paired Fitbit Inspire 3  
**Outcome:** **Test not run.** A critical source-level blocker was identified in `DtlsProbeStateMachine.kt`. This report documents why execution was deferred and the exact change required to unblock the test.

---

## Summary

The BlueBit Android app is structurally ready for a live read-only DTLS/CoAP smoke test: the manifest declares the required BLE and location permissions, runtime permission requests are present, the native `libxp.so` artifact exists, and the project compiles successfully.

However, `DtlsProbeStateMachine.onDtlsStatusChange` treats native DTLS state `3` (`TLS_STATE_ERROR`) as a terminal failure. On an already-paired Fitbit Inspire 3, the tracker first presents a MobileData PSK identity; the native resolver returns `-10601` (`GG_ERROR_TLS_UNKNOWN_IDENTITY`) and emits state `3`. The native server then auto-resets and retries with the `BOOTSTRAP` identity, which succeeds. Because the Kotlin state machine completes the session deferred with `false` on state `3`, the probe aborts before the successful bootstrap retry can occur.

Until this state-machine logic is changed to ignore transient `TLS_STATE_ERROR` events and wait only for `TLS_STATE_SESSION` (state `2`), a live test is predicted to fail during handshake.

---

## Pre-flight Checklist

| Item | Status | Evidence |
|---|---|---|
| Goal defined: establish DTLS session and send `GET /.well-known/core` | ✅ | `CoapReadOnlyProbe.runProbe` default path `".well-known/core"` |
| Call path traced through BLE → Gattlink → DTLS → CoAP | ✅ | `MainActivity.kt` → `CoapReadOnlyProbe.kt` → `GoldenGate.kt` → `Stack.kt` → `CoapEndpoint.kt` |
| Native library present | ✅ | `app/src/androidMain/jniLibs/arm64-v8a/libxp.so` (426,528 bytes) |
| Manifest BLE/location/internet permissions | ✅ | `AndroidManifest.xml`: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `INTERNET` |
| Runtime permission requests | ✅ | `MainActivity.requestBlePermissions()` requests location, BLE scan/connect, and notifications |
| Build compiles | ✅ | `./gradlew tasks --all` and `app:compileDebugKotlinAndroid` both `BUILD SUCCESSFUL` |
| ADB available | ✅ | `adb.exe` found; no device currently attached |
| DTLS state machine safe for MobileData fallback | ❌ | `DtlsProbeStateMachine.kt` completes `false` on state `3` — **blocker** |

---

## Critical Blocker: `DtlsProbeStateMachine` aborts on `TLS_STATE_ERROR`

### Where the failure happens

The native DTLS status listener in both probes delegates to `DtlsProbeStateMachine.onDtlsStatusChange`:

```kotlin
// CoapReadOnlyProbe.kt (approx. line 422)
when (machine.onDtlsStatusChange(state, code)) {
    DtlsStatusInterpretation.SESSION_ESTABLISHED -> dtlsSessionDeferred.complete(true)
    DtlsStatusInterpretation.ERROR -> dtlsSessionDeferred.complete(false)
    DtlsStatusInterpretation.PENDING -> { /* wait */ }
}

// DtlsBootstrapProbe.kt (approx. line 398)
when (machine.onDtlsStatusChange(state, code)) {
    DtlsStatusInterpretation.SESSION_ESTABLISHED -> dtlsSessionDeferred.complete(true)
    DtlsStatusInterpretation.ERROR -> dtlsSessionDeferred.complete(false)
    DtlsStatusInterpretation.PENDING -> { /* wait */ }
}
```

Inside the state machine, state `3` is mapped to a terminal error:

```kotlin
// DtlsProbeStateMachine.kt (lines 99-113)
fun onDtlsStatusChange(state: Int, code: Int): DtlsStatusInterpretation {
    _dtlsState = state
    _dtlsCode = code
    return when (state) {
        DTLS_STATE_SESSION -> {
            transitionTo(ProbeStage.SESSION_ESTABLISHED)
            DtlsStatusInterpretation.SESSION_ESTABLISHED
        }
        DTLS_STATE_ERROR -> {
            fail("DTLS error state=$state code=$code")
            DtlsStatusInterpretation.ERROR
        }
        else -> DtlsStatusInterpretation.PENDING
    }
}
```

### Why this is wrong for the Inspire 3

The Inspire 3 handshake sequence observed in prior analysis is:

```text
onDtlsStatusChange state=1 code=0   // handshake
onDtlsStatusChange state=3 code=-10601   // MobileData identity rejected
onDtlsStatusChange state=0 code=0   // native auto-reset
onDtlsStatusChange state=1 code=0   // re-handshake
onDtlsStatusChange state=2 code=0   // BOOTSTRAP accepted, session up
```

The native stack automatically retries with `BOOTSTRAP` after the MobileData identity fails. The Kotlin layer must therefore record state `3` for diagnostics but **not** complete the deferred until state `2` (`TLS_STATE_SESSION`) is seen. The reference implementation (`StackPeer.kt`) follows exactly this pattern: it waits for `TLS_STATE_SESSION` and ignores intermediate error states because “there are retries built into the XP library.”

### Proposed fix

Change `DtlsProbeStateMachine.onDtlsStatusChange` so that `DTLS_STATE_ERROR` is treated as pending/non-terminal, preserving the error details for later reporting:

```kotlin
fun onDtlsStatusChange(state: Int, code: Int): DtlsStatusInterpretation {
    _dtlsState = state
    _dtlsCode = code
    return when (state) {
        DTLS_STATE_SESSION -> {
            transitionTo(ProbeStage.SESSION_ESTABLISHED)
            DtlsStatusInterpretation.SESSION_ESTABLISHED
        }
        DTLS_STATE_ERROR -> {
            // Transient error: the native server auto-resets and retries
            // (e.g., MobileData identity rejected before BOOTSTRAP succeeds).
            // Record the error but keep waiting for SESSION_ESTABLISHED.
            _error = "DTLS transient error state=$state code=$code"
            DtlsStatusInterpretation.PENDING
        }
        else -> DtlsStatusInterpretation.PENDING
    }
}
```

With this change, the probe’s deferred will only complete successfully on state `2`; state `3` becomes visible in the final `ProbeResult.error` if the overall probe times out, but it no longer aborts the handshake early.

### Diff

```diff
--- a/BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/DtlsProbeStateMachine.kt
+++ b/BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/DtlsProbeStateMachine.kt
@@ -96,7 +96,7 @@ class DtlsProbeStateMachine {
      * Interprets a DTLS status notification from the native stack.
      * State 2 (TLS_STATE_SESSION) means the bootstrap session is up.
-     * State 3 is treated as a terminal error.
+     * State 3 is recorded but not terminal; the native server may auto-reset and retry.
      */
     fun onDtlsStatusChange(state: Int, code: Int): DtlsStatusInterpretation {
         _dtlsState = state
@@ -105,8 +105,10 @@ class DtlsProbeStateMachine {
             DTLS_STATE_SESSION -> {
                 transitionTo(ProbeStage.SESSION_ESTABLISHED)
                 DtlsStatusInterpretation.SESSION_ESTABLISHED
             }
             DTLS_STATE_ERROR -> {
-                fail("DTLS error state=$state code=$code")
-                DtlsStatusInterpretation.ERROR
+                // MobileData identity rejection (-10601) is transient;
+                // native auto-resets and retries with BOOTSTRAP.
+                _error = "DTLS transient error state=$state code=$code"
+                DtlsStatusInterpretation.PENDING
             }
             else -> DtlsStatusInterpretation.PENDING
         }
```

**Note:** Existing unit tests in `DtlsBootstrapProbeTest.kt` that assert `DtlsStatusInterpretation.ERROR` for state `3` will need to be updated to expect `PENDING` and verify that `machine.error` is set instead of `machine.stage == ProbeStage.FAILED`.

---

## Other Risks

| # | Risk | Severity | Notes |
|---|---|---|---|
| 1 | **arm64-only native library** | High | Only `jniLibs/arm64-v8a/libxp.so` is present. The app will crash with `UnsatisfiedLinkError` on x86 or 32-bit devices. Test must use an arm64 Android device. |
| 2 | **Main-thread init block** | Moderate | `GoldenGate.init()` calls `RunLoop.startAndWaitUntilReady()` on the launching coroutine, which starts from `lifecycleScope` on the main thread. A slow native run-loop could trigger an ANR. |
| 3 | **BLE write concurrency** | Moderate | `TxSink.putData` invokes `BluetoothGatt.writeCharacteristic()` directly from the native run-loop thread with no serialization queue; rapid back-to-back writes risk `GATT_BUSY` or dropped frames. |
| 4 | **Safe CoAP path uncertainty** | Moderate–High | `/.well-known/core` returned `4.04 Not Found` and `/sync/config` returned `4.01 Unauthorized` in prior captures. Any well-formed CoAP response proves end-to-end reachability, but a `2.xx` success cannot be guaranteed without firmware-specific path verification. |
| 5 | **Stale DTLS listener** | Low | `cleanup()` does not clear `stack.setDtlsStatusListener(null)`. After `stack.close()` native callbacks should stop, but a defensive null-set would reduce lifecycle risk. |
| 6 | **Response-code formatting bug** | Low (UX) | `4.04` is rendered as `4.4` in result cards/logs; does not affect the wire protocol. |

---

## Build Status

- `./gradlew tasks --all`: **BUILD SUCCESSFUL**
- `app:compileDebugKotlinAndroid`: **BUILD SUCCESSFUL**
- Gradle version: 8.10.2
- JDK: Eclipse Adoptium JDK 17
- AGP: 8.2.2
- Warnings observed: AGP tested up to `compileSdk = 34` but project uses `35`; SDK `package.xml` files read-only in this environment (non-fatal).

---

## Device Status

- ADB available: yes
- `adb devices`: no devices attached
- No on-device execution was performed.

---

## Decision

**The live bootstrap test was not run.**

The only critical blocker is the Kotlin DTLS state machine’s handling of `TLS_STATE_ERROR`. Running the test now would almost certainly produce a false-negative handshake failure on an already-paired Inspire 3, because the probe aborts during the MobileData → BOOTSTRAP fallback before the successful retry.

---

## Exact Next Steps

1. **Fix `DtlsProbeStateMachine.kt`** as shown in the diff/code snippet above (treat `DTLS_STATE_ERROR` as non-terminal; record the error; only complete successfully on `DTLS_STATE_SESSION`).
2. **Update affected unit tests** in `DtlsBootstrapProbeTest.kt` to reflect that state `3` now returns `PENDING` and sets `machine.error` without moving to `FAILED`.
3. **Rebuild** the debug APK:
   ```bash
   cd G:/Dev/BlueBit/BlueBitApp
   ./gradlew app:assembleDebug
   ```
4. **Attach an arm64 Android test device** with Bluetooth and Location enabled.
5. **Grant permissions** when prompted: Location, Bluetooth scan, Bluetooth connect.
6. **Run the app**, scan/select the already-paired Inspire 3, and execute:
   - **“Probe DTLS Bootstrap”** first. Expected sequence after the fix:
     - `state=1 code=0` (handshake)
     - `state=3 code=-10601` (MobileData rejected — **ignored**)
     - `state=0 code=0` (native auto-reset)
     - `state=1 code=0` (re-handshake)
     - `state=2 code=0` (session established)
   - **“Probe CoAP Read-Only”** next. Treat any non-timeout CoAP response (`2.xx` or `4.xx`) as proof of end-to-end connectivity.

---

*Report generated from verified inputs:*
- `G:\Dev\BlueBit\prototype_analysis\LIVE_BOOTSTRAP_PREFLIGHT.md`
- `G:\Dev\BlueBit\prototype_analysis\PROBE_AND_BUILD_NOTES.md`
- `G:\Dev\BlueBit\BlueBitApp\app\src\commonMain\kotlin\bluebit\fitbit\diagnostics\DtlsProbeStateMachine.kt`
