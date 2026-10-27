# BlueBit Background BLE Diagnostic Logger — Implementation Report

**Date:** 2026-10-18  
**APK:** `app/build/outputs/apk/debug/app-debug.apk`  
**Built:** 2026-10-18 14:04:02 +01:00  
**Test device:** Samsung Galaxy S4 (GT-I9505), Android 12 (API 31), ADB over Wi-Fi `192.168.1.144:53916`

## Summary

Implemented a user-controlled background BLE diagnostic logger. A foreground service writes timestamped BLE events to readable `.txt` files in `Downloads/BlueBit/` via MediaStore, with a required notification, bounded file size, periodic flushing, and thread-safe batching.

## Files Changed

| File | Change |
|------|--------|
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleLogWatchdog.kt` | Added optional `fileLogger` sink so every watchdog entry can be forwarded to the file logger. |
| `app/src/androidMain/kotlin/bluebit/core/bluetooth/android/BleDiagnosticLogger.kt` | New singleton: bounded in-memory buffer, `isRunning` StateFlow, start/stop service helpers, consumer registration for the service. |
| `app/src/androidMain/kotlin/bluebit/core/bluetooth/android/BleDiagnosticLogService.kt` | New foreground `Service`: notification channel, foreground notification, HandlerThread for I/O, batch flush every 2 s / 100 entries, MediaStore writes on Android 10+, direct Downloads writes on Android 8–9, 5 MB file rotation, error reporting via notification. |
| `app/src/androidMain/kotlin/bluebit/MainActivity.kt` | Wired `bleLogWatchdog.fileLogger` to `BleDiagnosticLogger`; added `isDiagnosticLogging` state; passed toggle callback to `BlueBitAppShell`; added `CONNECT_BUTTON_PRESSED` watchdog log; requested `WRITE_EXTERNAL_STORAGE` on API < 29. |
| `app/src/commonMain/kotlin/bluebit/BlueBitAppShell.kt` | Added `diagnosticLoggingEnabled` / `onDiagnosticLoggingToggle` parameters (with defaults for desktop); repurposed Settings switch; added `CONNECT_BUTTON_PRESSED`/`DISCONNECT_BUTTON_PRESSED` watchdog logs in `onToggleConnection`. |
| `app/src/androidMain/AndroidManifest.xml` | Added `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28); declared `BleDiagnosticLogService` with `foregroundServiceType="connectedDevice"`. |

## Design Notes

- **No system logcat collection:** only BlueBit BLE events emitted through `bleLogWatchdog` and explicit UI hooks are logged.
- **No upload:** all writes are local to device Downloads.
- **No firmware update changes:** only diagnostic logging code was touched.
- **Thread safety:** producers add entries to a thread-safe `ArrayBlockingQueue`; the service drains and writes on a dedicated `HandlerThread`. File writes use `ContentResolver.openOutputStream(uri, "wa")` for append.
- **File bounds:** each log file is capped at 5 MB; a new timestamped file is created when the limit is reached.
- **Existing diagnostics preserved:** all existing `bleLogWatchdog` calls in `AndroidBleScanner`, `AndroidBleConnection`, and Fitbit diagnostics are automatically captured.

## Build & Test Results

| Check | Command | Result |
|-------|---------|--------|
| Android compile | `./gradlew :app:compileDebugKotlinAndroid` | ✅ SUCCESS |
| Unit tests | `./gradlew test` | ✅ SUCCESS (no source) |
| Desktop tests | `./gradlew desktopTest` | ✅ SUCCESS |
| Debug APK | `./gradlew :app:assembleDebug` | ✅ SUCCESS |

## Device Verification

1. Installed APK on Galaxy S4 via `adb install -r app-debug.apk`.
2. Granted `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` via `pm grant`.
3. Launched app, navigated to **Settings**, toggled **Debug Logging** on.
4. Confirmed foreground service started (`BlueBitBleDiagSvc: Foreground service started`).
5. Confirmed notification posted (`channel=bluebit_ble_diagnostic`, `id=1001`).
6. Navigated to **Devices**, started scan.
7. Verified log file created: `/sdcard/Download/BlueBit/bluebit_ble_2026-10-18_14-07-08.txt`.
8. Toggled Debug Logging off; service stopped.

### Sample Log Content

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

File size after stop: **651 bytes**.

### Caveats

- No BLE peripheral was available during verification, so `connectGatt()` / GATT state-change lines were not observed live. The code path is covered by the existing `AndroidBleConnection` watchdog logs, which now flow to the file logger.
- `POST_NOTIFICATIONS` could not be granted via `pm grant` on this custom ROM (`Unknown permission`), but the notification was still posted successfully from the foreground service.

## Deliverables

- ✅ Changed source files listed and explained.
- ✅ Build and test results recorded.
- ✅ APK path and timestamp documented.
- ✅ Device-tested: log file created in `Downloads/BlueBit/` and contains real BLE scan events.
