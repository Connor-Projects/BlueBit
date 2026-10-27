# BLE Diagnostic Logger Verification

**Date:** 2026-10-18
**Verifier:** ble-logger-verifier
**Scope:** Independently verify the BlueBit background BLE diagnostic logger implementation.

## Files Inspected

All requested files exist under `G:/Dev/BlueBit/BlueBitApp`:

1. `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleLogWatchdog.kt`
2. `app/src/androidMain/kotlin/bluebit/core/bluetooth/android/BleDiagnosticLogger.kt`
3. `app/src/androidMain/kotlin/bluebit/core/bluetooth/android/BleDiagnosticLogService.kt`
4. `app/src/androidMain/kotlin/bluebit/MainActivity.kt` (logger wiring only)
5. `app/src/commonMain/kotlin/bluebit/BlueBitAppShell.kt` (toggle only)
6. `app/src/androidMain/AndroidManifest.xml`

## Check 1 — Foreground Service & Notification Channel

**Result: PASS**

*   `BleDiagnosticLogService` extends `android.app.Service`.
*   It calls `startForeground(NOTIFICATION_ID, buildNotification(...))` before doing any work in `startLogging()`.
*   `createNotificationChannel()` creates a `NotificationChannel` with id `bluebit_ble_diagnostic` on API 26+.
*   `AndroidManifest.xml` declares the service with:
    *   `android:name="bluebit.core.bluetooth.android.BleDiagnosticLogService"`
    *   `android:foregroundServiceType="connectedDevice"`
    *   `android:exported="false"`
*   Required permissions are present: `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

## Check 2 — No Network / Upload Code in Logger Files

**Result: PASS**

A manual review of the three logger files and the service confirms:

*   No HTTP/HTTPS clients (no `HttpURLConnection`, `OkHttp`, `Ktor`, `Retrofit`).
*   No socket or raw network imports.
*   No cloud, FTP, upload, or telemetry APIs.
*   The only outputs are local `.txt` files written to `Downloads/BlueBit` via `MediaStore` (Android 10+) or direct filesystem access (Android 9 and below).

## Check 3 — No System Logcat Collection

**Result: PASS**

*   None of the logger files spawn a `ProcessBuilder`, call `Runtime.getRuntime().exec()`, or read from `logcat`.
*   The service only consumes `BleLogEntry` objects produced by the in-app `BleLogWatchdog` / `BleDiagnosticLogger` pipeline.

## Check 4 — APK Timestamp and Size

**Result: VERIFIED**

```
G:/Dev/BlueBit/BlueBitApp/app/build/outputs/apk/debug/app-debug.apk
Size:  27,364,473 bytes (≈26.1 MiB)
MTime: 2026-10-18 14:06
```

## Check 5 — ADB / Log File on Device

**Result: DEVICE REACHABLE, LOG FILE PRESENT**

`adb devices` reported two entries for what appears to be the same physical device (network + mDNS TLS):

```
192.168.1.144:53916                       device
adb-RF1D53YHMNX-EIxJX8._adb-tls-connect._tcp device
```

The specific model (Galaxy S4) cannot be confirmed from the `adb devices` output alone.

Both transports show the same `Downloads/BlueBit/` directory:

```
-rw-rw---- 1 root everybody 651 2026-10-18 14:07 bluebit_ble_2026-10-18_14-07-08.txt
```

First 30 lines of the latest log file (`bluebit_ble_2026-10-18_14-07-08.txt`):

```
2026-10-18 13:07:08.054 [INFO] SCAN: SCAN_START: Fitbit filtered scan
2026-10-18 13:07:08.103 [INFO] SCAN: SCAN_STARTED_OK: mode=FILTERED(filterCount=6)
2026-10-18 13:07:13.159 [INFO] SCAN: SCAN_STARTED_OK: mode=FILTERED(filterCount=6)
2026-10-18 13:07:18.192 [INFO] SCAN: SCAN_STARTED_OK: mode=FILTERED(filterCount=6)
2026-10-18 13:07:23.224 [INFO] SCAN: SCAN_STARTED_OK: mode=FILTERED(filterCount=6)
2026-10-18 13:07:28.256 [INFO] SCAN: SCAN_STARTED_OK: mode=FILTERED(filterCount=6)
2026-10-18 13:07:33.358 [INFO] SCAN: SCAN_STARTED_OK: mode=FILTERED(filterCount=6)
2026-10-18 13:07:38.404 [INFO] SCAN: SCAN_STARTED_OK: mode=FILTERED(filterCount=6)
```

The file contains real BLE scan events and the logger is functioning.

## Check 6 — Build

**Result: PASS**

Ran:

```
cd G:/Dev/BlueBit/BlueBitApp
gradlew.bat :app:compileDebugKotlinAndroid
```

Output:

```
> Task :app:compileDebugKotlinAndroid UP-TO-DATE
BUILD SUCCESSFUL in 1s
15 actionable tasks: 15 up-to-date
```

## Minor Observations

1. **Filename / entry timestamp mismatch:** The log file is named with local time (`2026-10-18_14-07-08`) but the entries inside are formatted as UTC (`2026-10-18 13:07:08`). `BleLogWatchdog.formattedTimestamp()` computes hours from epoch seconds (UTC), while the service uses `SimpleDateFormat` (local timezone) for the filename. This is cosmetic but may confuse users.
2. **Notification newline:** The service uses `"BLE diagnostic logging active\nFile: $currentFileName"` as notification content text. Some Android versions may not render the embedded newline well.
3. **Build warnings:** Gradle emits the expected `compileSdk = 35` / Android Gradle Plugin 8.2.2 compatibility warning and read-only SDK package.xml warnings. These do not block compilation.

## Conclusion

The BLE diagnostic logger implementation is correct and complete for its intended scope: a local, file-only, foreground-service-based logger with no network upload and no system logcat collection. The project builds, the APK is present, and the device log file contains live BLE events.
