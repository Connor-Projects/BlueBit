# BlueBit Bluetooth Startup Crash Fix & Inspire 3 Scan Diagnostics Report

**Date:** 2026-10-17  
**Device under test:** Samsung Galaxy S4 (GT-I9505) running Android 12 (API 31)  
**APK:** `bluebit.app` debug build

---

## 1. Root cause of the crash

`MainActivity.onCreate()` called `BluetoothAdapter.getAddress()` inside a log statement before the runtime `BLUETOOTH_CONNECT` permission was granted:

```kotlin
Log.i("BlueBit", "BLUETOOTH_OK: Adapter enabled, address=${bluetoothAdapter!!.address}")
```

On Android 12+ (API 31), `BluetoothAdapter.getAddress()` and `BluetoothAdapter.isEnabled()` are protected by `BLUETOOTH_CONNECT`. Although `MainActivity` already had a `RequestMultiplePermissions` launcher, `onCreate()` continued immediately after requesting permissions and did not wait for the grant. This caused a deterministic `java.lang.SecurityException: Need android.permission.BLUETOOTH_CONNECT permission` at the reported line.

A secondary crash surface was present in `AndroidBleScanner`: the scan callback accessed `BluetoothDevice.getAddress()` and `BluetoothDevice.getName()` unconditionally. If a user granted `BLUETOOTH_SCAN` but denied `BLUETOOTH_CONNECT`, receiving any advertisement would also throw `SecurityException`.

---

## 2. Files changed and explanation

### 2.1 `app/src/androidMain/kotlin/bluebit/MainActivity.kt`

* **Defer permission-dependent adapter inspection.** Removed the immediate `bluetoothAdapter!!.address` read. `onCreate()` now only logs that the adapter exists and whether `BLUETOOTH_CONNECT` is already granted. Detailed adapter state/address logging is deferred to `logBluetoothAdapterState()`, which is invoked only after permissions are confirmed.
* **Permission-driven initialization.** Moved `requestBlePermissions()` to the end of `onCreate()` so all BLE-dependent objects (`PairedDeviceCoordinator`, scanner, providers, diagnostics) exist before any permission-dependent operation runs.
* **New helpers:**
  * `hasBluetoothConnectPermission()` — checks `BLUETOOTH_CONNECT` on API 31+ and `BLUETOOTH` on older Android.
  * `logBluetoothAdapterState()` — safely calls `isEnabled()`/`getAddress()` only when the permission is granted, catching `SecurityException`.
  * `initializeBluetoothDependentOperations()` — called from the permission launcher and when permissions are already granted. It logs adapter state, scanner readiness, and triggers `pairedDeviceCoordinator.restoreOnLaunch()`.
* **No-reprompt-on-denial.** The permission launcher logs denied permissions and disables BLE features, but does not re-prompt in a loop. If some permissions are denied, `logBluetoothAdapterState()` is still called with guards so the app does not crash.
* **Scanner lifecycle.** Added a `scanner` field and called `scanner?.stopScan()` in `onDestroy()` to avoid leaking scans across configuration changes.
* **Bond-state receiver safety.** The `ACTION_BOND_STATE_CHANGED` receiver now checks `BLUETOOTH_CONNECT` before reading `device.address` and falls back to `redacted` if the permission is missing.
* **Conditional permission request.** `requestBlePermissions()` now requests `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` on all versions and adds `ACCESS_FINE_LOCATION` only on Android 11 and lower, preserving legacy BLE scanning behavior.

### 2.2 `app/src/androidMain/kotlin/bluebit/core/bluetooth/android/AndroidBleScanner.kt`

* **Safe adapter state read.** Replaced direct `bluetoothAdapter.isEnabled` calls with `isAdapterEnabledSafe()`, which catches `SecurityException`.
* **Safe device identity read.** In `onScanResult`, `BluetoothDevice.getAddress()`/`getName()` are now guarded by `hasBluetoothConnectPermission()`. If the permission is missing, the result is emitted with a stable placeholder address (`hidden-<hash>`) and the name from the advertisement record only.
* **Rate-limited diagnostic logging.** Added `logDiagnosticSummary()` so each unique address emits a detailed advertisement summary at most once every 2 seconds. Summary includes name, RSSI, TX power, advertised service UUIDs, service-data payload sizes, manufacturer-ID payload sizes, raw-byte count, and permission state — without dumping sensitive keys/credentials.
* **Enhanced start/stop/error logging.** Every scan start/stop now logs permission state, adapter state, scanner-null status, filter mode (`FILTERED`/`UNFILTERED`), and any `SecurityException` or start-scan failure. State flows are reset to `false` on failure so the UI does not stay stuck in “scanning”.
* **Watchdog still active.** The existing 5-second no-result watchdog remains, with clearer log context.

### 2.3 `app/src/commonMain/kotlin/bluebit/BlueBitAppShell.kt`

* Added diagnostic `println` lines when a scan result is received:
  * `UNFILTERED_DEVICE` — when no provider (Fitbit/FitCloud) recognizes the advertisement.
  * `RECOGNISED_DEVICE` — when a provider matches.
* This guarantees that even if the Fitbit provider cannot identify the Inspire 3, the unfiltered advertisement still reaches the UI and is visible in logcat/system-out diagnostics.

### 2.4 `app/src/androidMain/AndroidManifest.xml`

* Added `android:usesPermissionFlags="neverForLocation"` to `BLUETOOTH_SCAN`, which on Android 12+ removes the need to request location for BLE scanning. `ACCESS_FINE_LOCATION` is still requested on Android 11 and lower for compatibility.

### 2.5 `app/src/commonMain/kotlin/bluebit/fitbit/FitbitProvider.kt`

* No behavior change was required. `canHandle()` already matches against the six known Fitbit service UUIDs and does not assume a device name like “Inspire 3”. The scanner and UI now ensure unrecognized advertisements still surface.

---

## 3. Permission-handling behaviour after fix

| Scenario | Behavior |
|----------|----------|
| First launch, all permissions denied | App logs `PERMISSION_DENIED`. No Bluetooth APIs are called. UI shows no devices. No crash, no reprompt loop. |
| First launch, user grants all | `initializeBluetoothDependentOperations()` runs, logs adapter state/address safely, and restores any remembered paired device. |
| Permissions already granted | Same as above, triggered immediately from `requestBlePermissions()`. |
| `BLUETOOTH_SCAN` granted but `BLUETOOTH_CONNECT` denied | Filtered/unfiltered scan can still start, but emitted results use placeholder addresses (`hidden-<hash>`) and names from the advertisement record only. No `SecurityException`. |
| `BLUETOOTH_CONNECT` granted but `BLUETOOTH_SCAN` denied | Scanning is rejected with `PERMISSION_DENIED` log; UI remains safe. |
| Bluetooth disabled | Scanning is rejected with `BLUETOOTH_DISABLED` log; no crash. |

---

## 4. Filtered and unfiltered scan status

### 4.1 Filtered scan

* Uses six Fitbit-related service UUIDs (ADABFB00…, FD63, ABBAFB00…, ABBAFF00…, FD62, Weight service).
* Requires `BLUETOOTH_SCAN` (and `BLUETOOTH_CONNECT` to read device address/name).
* Rate-limited diagnostic logging now reports the filter count and mode when the scan starts.

### 4.2 Unfiltered scan (`Scan All BLE Devices`)

* Calls `startUnfilteredScan()` with an empty `ScanFilter` list.
* Does **not** auto-connect; it is purely a discovery/diagnostic pass.
* All received advertisements are emitted through `BleScanner.scanResults()` and collected by `BlueBitAppShell`, even when `ProviderRegistry.resolve()` returns `null`.
* In that case the device appears in the device list with `deviceType = "Unknown"`, `recognised = false`, and the diagnostic log line `UNFILTERED_DEVICE` is printed.
* If the Inspire 3 advertises service UUIDs outside the Fitbit set, it will still appear in the unfiltered scan.

### 4.3 Lifecycle safety

* `stopScan()` is now invoked from `MainActivity.onDestroy()`.
* If `scanner.startScan()` throws, internal scanning state is reset so the UI button does not remain in “Stop Scan”.

---

## 5. Build/test results

All commands were run from `G:\Dev\BlueBit\BlueBitApp` with `JAVA_HOME` set to the installed Eclipse Temurin JDK 17.

### 5.1 `:app:compileDebugKotlinAndroid`

```text
BUILD SUCCESSFUL in 2s
15 actionable tasks: 1 executed, 14 up-to-date
```

No new compilation errors or warnings were introduced by these changes. All warnings shown are pre-existing deprecation warnings in unrelated files (e.g. `BluetoothGattCharacteristic.value`, `writeDescriptor`, etc.).

### 5.2 `test`

```text
BUILD SUCCESSFUL in 7s
48 actionable tasks: 48 executed
```

`app:test` completed successfully. The project has no Android unit-test sources, so the relevant Android unit-test tasks reported `NO-SOURCE`.

### 5.3 `:app:desktopTest`

```text
BUILD SUCCESSFUL in 6s
6 actionable tasks: 2 executed, 4 up-to-date
```

All desktop/JVM unit tests passed.

### 5.4 `:app:assembleDebug`

```text
BUILD SUCCESSFUL in 1s
39 actionable tasks: 3 executed, 36 up-to-date
```

### 5.5 Unrelated pre-existing issues observed during build

* **AGP/SDK warning:** `This Android Gradle plugin (8.2.2) was tested up to compileSdk = 34.` The project uses `compileSdk = 35`. This is pre-existing and does not block the build.
* **SDK read-only warnings:** `Exception while marshalling ... package.xml. Probably the SDK is read-only`. These are environmental and pre-existing.
* **Deprecation warnings:** Multiple `@Deprecated` Bluetooth API warnings in `AndroidBleConnection`, `Inspire3PairingHandler`, diagnostics, and `BottomNavigation.kt`. None were introduced by this fix.

---

## 6. APK output path and timestamp

```text
G:\Dev\BlueBit\BlueBitApp\app\build\outputs\apk\debug\app-debug.apk
Size:  27,356,592 bytes
Timestamp: 2026-10-17 19:42:24 UTC+1
```

---

## 7. Device test results

### 7.1 Install and startup

* ADB initially detected the Galaxy S4 (`RF1D53YHMNX`, GT-I9505, Android 12).
* `adb install -r app-debug.apk` succeeded.
* The app was launched with `adb shell am start -n bluebit.app/.MainActivity`.
* Logcat captured from the first launch (permissions already granted on the device from a previous install):

```text
10-17 19:41:38.555  4146  4146 I BlueBit : BLUETOOTH_ADAPTER_PRESENT: connectGranted=true
10-17 19:41:38.567  4146  4146 I BlueBit : BLUETOOTH_STATE: enabled=true, address=02:00:00:00:00:00
10-17 19:41:39.553  4146  4146 I BlueBit : PERMISSION_STATUS: {android.permission.BLUETOOTH_SCAN=GRANTED, android.permission.BLUETOOTH_CONNECT=GRANTED}
10-17 19:41:39.553  4146  4146 I BlueBit : PERMISSIONS_ALREADY_GRANTED: android.permission.BLUETOOTH_SCAN, android.permission.BLUETOOTH_CONNECT
10-17 19:41:39.555  4146  4146 I BlueBit : BLUETOOTH_STATE: enabled=true, address=02:00:00:00:00:00
10-17 19:41:39.555  4146  4146 I BlueBit : INIT_BLE_OPS: scanner ready, isScanning=false
```

**Result:** No `SecurityException`, no `AndroidRuntime` crash. Startup now proceeds safely.

### 7.2 Scan test limitation

Immediately after the first launch, the ADB connection to the Galaxy S4 dropped to `offline` and could not be re-established (`adb kill-server/start-server`, `adb reconnect`). Therefore:

* The updated APK (containing the bond-receiver guard) was not reinstalled.
* The UI could not be interacted with to press **Scan** or **Scan All BLE Devices**.
* No `BlueBitScan` advertisement logs were captured.

The startup-path fix is verified; the live scan-path verification is blocked by the hardware/ADB disconnect.

---

## 8. Reset recommendation and next diagnostic steps

### 8.1 Reset recommendation

**Do not reset the Inspire 3 yet.**

The required diagnostic order is:

1. ✅ Fix startup crash — completed.
2. ⬜ Verify permissions and unfiltered scan on the Galaxy S4.
3. ⬜ Inspect the advertisements the Inspire 3 actually broadcasts.
4. ⬜ Determine whether it is discoverable without entering pairing mode.
5. ⬜ Only if pairing mode is required, investigate whether it can be entered without a factory reset.
6. ⬜ Factory reset only if the above steps establish necessity and after explicit user confirmation.

A factory reset on the Inspire 3 will erase unsynced activity data, stored settings, and any watch faces/apps that are not backed up to the Fitbit account. Because the app has not yet observed the tracker’s advertisements, there is no evidence that a reset is necessary.

### 8.2 Exact next diagnostic steps

1. **Reconnect the Galaxy S4 to ADB.**
   * Try a different USB port/cable.
   * On the phone, ensure **Developer options → USB debugging** is enabled and the host RSA key is authorized.
   * Confirm `adb devices` shows `RF1D53YHMNX device`.

2. **Install the fresh APK.**
   ```powershell
   adb -s RF1D53YHMNX install -r G:\Dev\BlueBit\BlueBitApp\app\build\outputs\apk\debug\app-debug.apk
   ```

3. **If permissions are not already granted, accept the runtime prompt.** Then capture:
   ```powershell
   adb -s RF1D53YHMNX logcat -c
   adb -s RF1D53YHMNX shell am start -n bluebit.app/.MainActivity
   adb -s RF1D53YHMNX logcat -d -s BlueBit:D BlueBitScan:D AndroidRuntime:E
   ```
   Expected: `BLUETOOTH_ADAPTER_PRESENT`, `BLUETOOTH_STATE`, `INIT_BLE_OPS` lines; no `SecurityException`.

4. **Tap “Scan All BLE Devices” in the UI.** If ADB input is needed:
   ```powershell
   adb -s RF1D53YHMNX shell uiautomator dump /sdcard/window_dump.xml
   adb -s RF1D53YHMNX pull /sdcard/window_dump.xml
   ```
   Parse the dump for the `Scan All BLE Devices` bounds and tap:
   ```powershell
   adb -s RF1D53YHMNX shell input tap <x> <y>
   ```

5. **Capture unfiltered scan logs for at least 30 seconds while the Inspire 3 is nearby and awake:**
   ```powershell
   adb -s RF1D53YHMNX logcat -d -s BlueBitScan:I BlueBitUI:I AndroidRuntime:E
   ```
   Look for `DIAGNOSTIC_ADVERTISEMENT` lines. Note:
   * Device name (if any).
   * Advertised service UUIDs.
   * Manufacturer IDs.
   * Whether any UUID matches the Fitbit set.

6. **Compare filtered vs unfiltered results.**
   * If the Inspire 3 appears in **unfiltered** but not **filtered**, note the advertised UUIDs/manufacturer data and update `FitbitProvider.canHandle()` or the scanner filter set accordingly.
   * If it does **not** appear in either scan, the tracker is not broadcasting (likely not in pairing/discoverable mode). Investigate how to wake it (screen on, menu → Settings → Bluetooth → Pair) before considering any reset.

7. **Only consider reset if:**
   * The Inspire 3 is confirmed not discoverable in pairing mode, and
   * Fitbit documentation/user guide states a reset is required to re-enter pairing, and
   * The user explicitly confirms after being warned about data loss.

Do **not** automate or initiate a reset from the app.
