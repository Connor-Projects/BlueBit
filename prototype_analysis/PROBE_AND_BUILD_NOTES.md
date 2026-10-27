# BlueBit Probe & Build Analysis Notes

**Date:** 2026-10-17
**Project:** `G:/Dev/BlueBit/BlueBitApp`
**Scope:** Inventory existing DTLS/CoAP probe implementations and verify the local build environment for a live BlueBit test (no on-device execution yet).

---

## 1. Probe Summaries

### 1.1 `DtlsBootstrapProbe` (`BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt`)

* **What it does:**
  * Establishes a BLE connection to a Fitbit Inspire 3.
  * Discovers Gattlink service (`ABBAFF00-E56A-484C-B832-8B17CF6CBFE8`) and characteristics.
  * Enables Service Changed indications and Gattlink notifications.
  * Performs GattDatabase validation via the ephemeral pointer characteristic (`AC2F0145-8182-4BE5-91E0-2992E6B40EBB`) when present.
  * Starts the GoldenGate `DtlsSocketNetifGattlinkStackConfig` stack with the public bootstrap PSK.
  * Stops as soon as the DTLS session is established (state `2` / `DTLS_STATE_SESSION`).
  * **Does not send any CoAP application requests.**
* **Constructor dependencies:** `private val context: Context` only.
* **How it is started/cancelled:**
  * Started by calling `suspend fun runProbe(device: BluetoothDevice): ProbeResult` from a coroutine.
  * No explicit in-flight cancellation API; the probe uses internal `withTimeoutOrNull(...)` timeouts:
    * BLE connection: 15s
    * Service discovery: 15s
    * Gatt steps (Service Changed, Gattlink notifications): 10s
    * DTLS handshake: 20s (`DtlsProbeStateMachine.DEFAULT_HANDSHAKE_TIMEOUT_MS`)
  * Resources are released in `private fun cleanup(result: ProbeResult): ProbeResult`.
* **Success/failure states it exposes:**
  * Returns a `ProbeResult` from `bluebit.fitbit.diagnostics`:
    * `stage: ProbeStage` – last reached stage (e.g. `SESSION_ESTABLISHED`, `FAILED`, `CLEANUP`).
    * `success: Boolean`
    * `error: String?`
    * `dtlsState: Int?`, `dtlsCode: Int?`
    * `txCount: Int`, `rxCount: Int`
  * `DtlsProbeStateMachine.success()` and `DtlsProbeStateMachine.fail(...)` build the result.
* **Read-only / safe to run:**
  * **Yes, read-only.** It only validates that DTLS bootstrap can complete. No writes or CoAP commands are issued.

### 1.2 `CoapReadOnlyProbe` (`BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt`)

* **What it does:**
  * Reuses the exact GoldenGate BLE + Gattlink + DTLS sequence from `DtlsBootstrapProbe`.
  * After DTLS session establishment, sends **exactly one** CoAP `GET` request.
  * Default path is `.well-known/core`; custom path can be passed via `runProbe(device, path)`.
  * Reports the CoAP response code, body length, and a redacted resource summary.
* **Constructor dependencies:** `private val context: Context` only.
* **How it is started/cancelled:**
  * Started by calling `suspend fun runProbe(device: BluetoothDevice, path: String = ".well-known/core"): CoapReadOnlyResult`.
  * Same timeout structure as `DtlsBootstrapProbe`, plus a 10s CoAP request timeout (`COAP_TIMEOUT_MS`).
  * Exposes a public `disconnect()` method that calls `cleanup()` to release all resources.
  * `MainActivity.onDestroy()` calls `coapReadOnlyProbe.disconnect()`.
* **Success/failure states it exposes:**
  * Returns `CoapReadOnlyResult` (`bluebit.core.diagnostics`):
    * `success: Boolean`
    * `dtlsEstablished: Boolean`
    * `coapSuccess: Boolean`
    * `responseCode: String?`
    * `responseLength: Int`
    * `resourceSummary: String`
    * `error: String?`
  * `MainActivity` stores the latest result in `lastCoapReadOnlyResult` (a `MutableState<CoapReadOnlyResult?>`) and passes it to `BlueBitAppShell`, which renders it via `DeviceDetailScreen`.
* **Read-only / safe to run:**
  * **Yes, read-only and safe.** It performs a single CoAP GET and does not mutate device state. It is manually triggered from the UI, not automatic.

### 1.3 `CoapReadOnlyProbeLogic` (`BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt`)

* **What it does:**
  * Hardware-independent helpers extracted so they can be unit-tested on the desktop target.
  * Creates a read-only `CoapRequestSpec` (GET, no query strings, leading `/` stripped).
  * Processes CoAP responses: classifies 2.x as success, redacts secrets (`psk=`, `token=`, etc.), parses link-format payloads, truncates summaries.
  * Provides `createTimeoutResult(...)` and `runCleanupActions(...)` helpers.
* **Constructor dependencies:** `object` – no constructor.
* **Read-only / safe:** Pure helpers; safe.

### 1.4 `DtlsProbeStateMachine` (`BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/DtlsProbeStateMachine.kt`)

* **What it does:**
  * Tracks probe lifecycle through `ProbeStage` enum: `NOT_STARTED` → `BLE_CONNECTION` → `BLE_DISCOVERY` → `GATTLINK_SERVICE_CHECK` → `GATTLINK_SETUP` → `DTLS_HANDSHAKE` → `SESSION_ESTABLISHED` / `FAILED` → `CLEANUP`.
  * Enforces allowed transitions via `transitionTo(...)`.
  * Records TX/RX byte counters.
  * Interprets native DTLS status callbacks:
    * state `2` → `SESSION_ESTABLISHED`
    * state `3` → error
  * Builds `ProbeResult` snapshots via `success()`, `fail(...)`, `cleanupResult()`.
* **Constructor dependencies:** None.
* **Read-only / safe:** Pure state machine; safe.

---

## 2. How Probes Are Triggered (`MainActivity.kt`)

* `MainActivity` instantiates both probes in `onCreate`:
  ```kotlin
  dtlsBootstrapProbe = DtlsBootstrapProbe(this)
  coapReadOnlyProbe = CoapReadOnlyProbe(this)
  ```
* They are exposed as `ProviderDiagnostic` actions inside the `FitbitProvider.diagnosticsFactory` lambda:
  * `id = "dtls_bootstrap_probe"`, label `"Probe DTLS Bootstrap"`
  * `id = "coap_readonly_probe"`, label `"Probe CoAP Read-Only"`
* Each action resolves the BLE address to a `BluetoothDevice`, launches a coroutine in `lifecycleScope`, and calls `runProbe(device)`.
* The CoAP result is captured into `lastCoapReadOnlyResult.value = result` so Compose recomposes and `DeviceDetailScreen` displays it via `CoapReadOnlyResultCard`.
* `MainActivity.onDestroy()` cleans up:
  ```kotlin
  coapReadOnlyProbe.disconnect()
  ```

---

## 3. Build Environment Status

### 3.1 Build files present

| File | Purpose |
|------|---------|
| `BlueBitApp/settings.gradle.kts` | Root settings; includes `:app`; configures repos (Gradle Plugin Portal, Maven Central, Google, JetBrains Compose dev, local insecure maven). |
| `BlueBitApp/app/build.gradle.kts` | App module: Kotlin Multiplatform (androidTarget + jvm desktop), Compose, Android app plugin, dependencies. |
| `BlueBitApp/gradle/libs.versions.toml` | Version catalog: AGP 8.2.2, Kotlin 2.0.0, Compose 1.6.11, Ktor 2.3.12, etc. |
| `BlueBitApp/gradle.properties` | `android.useAndroidX=true`, Kotlin code style, `org.gradle.java.home` → JDK 17, JVM heap. |
| `BlueBitApp/local.properties` | `sdk.dir=C:\Program Files (x86)\Android\android-sdk` |

### 3.2 Native library (`libxp.so`)

* **Exists:** `BlueBitApp/app/src/androidMain/jniLibs/arm64-v8a/libxp.so`
* **Size:** 426,528 bytes
* **Architecture:** `arm64-v8a`
* **Role:** GoldenGate native dependency loaded by `GoldenGate.init()` / stack classes.

### 3.3 Gradle wrapper

* `BlueBitApp/gradlew` (POSIX shell script) — exists
* `BlueBitApp/gradlew.bat` (Windows batch) — exists
* `BlueBitApp/gradle/wrapper/gradle-wrapper.jar` — exists (43,583 bytes)
* **Execution note:** In this sandbox, environment variables are not propagated to child processes, so `gradlew`/`gradlew.bat` fail their internal `JAVA_HOME` checks even though the JDK is installed. The wrapper works when invoked directly via the installed JDK 17:
  ```bash
  cd BlueBitApp
  "C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin/java.exe" \
    -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain tasks --all
  ```

### 3.4 `./gradlew tasks --all` result

**BUILD SUCCESSFUL** — Gradle 8.10.2 configured the project and listed all tasks. The daemon started and used JDK 17 from `org.gradle.java.home`.

### 3.5 Compilation sanity check

* Ran `app:compileDebugKotlinAndroid`.
* **Result:** `BUILD SUCCESSFUL` (all tasks up-to-date). Warnings noted:
  * AGP 8.2.2 was tested up to `compileSdk = 34`; project uses `compileSdk = 35`.
  * SDK `package.xml` files are read-only in this environment (non-fatal).

---

## 4. Android SDK / NDK / ADB

### 4.1 Android SDK

* **Path:** `C:\Program Files (x86)\Android\android-sdk`
* **Exists:** Yes
* **Installed components observed:**
  * `build-tools/36.0.0`
  * `platform-tools/`
  * `platforms/android-35`
  * `platforms/android-36`
* **NDK:** Not installed (no `ndk/` directory under the SDK).
  * **Note:** The project does not appear to build native code from source; it relies on the prebuilt `libxp.so` in `jniLibs/arm64-v8a/`, so the missing NDK is not currently a blocker.

### 4.2 ADB availability

* **ADB found:** Yes
  * `C:\DevTools\platform-tools-latest-windows\platform-tools\adb.exe`
  * Additional copy from scrcpy also on PATH.
* **`adb devices` output:**
  ```
  List of devices attached

  ```
  *No device is currently connected.* This is expected for a desk-side build check; no on-device execution was requested.

---

## 5. Recommended Minimal Runner for the Read-Only Probe

### 5.1 Current state

The read-only CoAP probe is **already wired into the existing UI flow**:

1. `MainActivity` creates `CoapReadOnlyProbe(this)`.
2. `FitbitProvider` registers a `ProviderDiagnostic(id = "coap_readonly_probe", label = "Probe CoAP Read-Only")`.
3. When a discovered Fitbit device is tapped, `DeviceDetailScreen` receives that diagnostic as a button.
4. Tapping the button calls `coapReadOnlyProbe.runProbe(device)` and displays the result in `CoapReadOnlyResultCard`.

Therefore, **no code change is required if you can scan and select a real Fitbit device** in the app.

### 5.2 Smallest code change for lab testing

If you want to trigger the probe without relying on BLE scanning/device recognition (e.g., with a hardcoded lab tracker address), the smallest change is to add a **temporary debug button in `SettingsScreen`** or a **one-shot launcher in `MainActivity`**.

Recommended approach:

* **File:** `BlueBitApp/app/src/commonMain/kotlin/bluebit/core/ui/screens/SettingsScreen.kt`
* **Change:** Add a new `SecondaryButton` labeled `"Run CoAP Read-Only Probe"` that accepts a device address and an `onRunCoapReadOnlyProbe: (String) -> Unit` callback.
* **File:** `BlueBitApp/app/src/androidMain/kotlin/bluebit/MainActivity.kt`
* **Change:** Pass the callback to `SettingsScreen`:
  ```kotlin
  SettingsScreen(
      ...
      onRunCoapReadOnlyProbe = { address ->
          val adapter = bluetoothAdapter ?: return@SettingsScreen
          if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
              != PackageManager.PERMISSION_GRANTED) {
              Log.e("BlueBit", "COAP_PROBE: BLUETOOTH_CONNECT not granted")
              return@SettingsScreen
          }
          lifecycleScope.launch {
              try {
                  val device = adapter.getRemoteDevice(address)
                  val result = coapReadOnlyProbe.runProbe(device)
                  lastCoapReadOnlyResult.value = result
                  Log.i("BlueBit", "COAP_READONLY_PROBE result=$result")
              } catch (e: Exception) {
                  Log.e("BlueBit", "COAP_READONLY_PROBE failed: $address", e)
              }
          }
      }
  )
  ```

### 5.3 Safety checklist before running on a live tracker

* Permissions `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `ACCESS_FINE_LOCATION` are already declared in `AndroidManifest.xml` and requested at runtime.
* The probe is read-only (single CoAP GET), but it still opens a DTLS session over BLE, so it will briefly wake the radio and may interrupt an existing paired connection.
* Run with `CoapReadOnlyProbe` (not `DtlsBootstrapProbe`) if the goal is read-only verification.
* Keep the device nearby; BLE connection timeout is 15s.
* If the tracker is bonded to another phone, the DTLS bootstrap using the public PSK may fail; test with a fresh/unpaired tracker when possible.
