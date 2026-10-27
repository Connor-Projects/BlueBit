# Phase 20 Follow-up — Notification-Free Inspire 3 Connection Flow (Investigation)

**Date:** 2026-10-25 23:52–23:58 (workstation clock)
**Objective:** Support in-app connection status and manual reconnect without unnecessarily requiring `POST_NOTIFICATIONS`. Google supervision is active on the phone; Android currently blocks BlueBit notifications.
**Scope of this file:** Phase 1 findings only (inspect and report). Implementation results go to a separate follow-up report in `phase20/`.

---

## 1. Workspace preservation check

- Branch `main` @ `7d2342fd4a730f3bdeab30c93bc919876150f15f`, `git status --porcelain` = **80 entries, 0 stashes** — identical to `baseline_git_status_porcelain.txt` (verified line-by-line count).
- Phase 18/19 evidence untouched (sizes/mtimes spot-checked); phase20 artefacts all present (`captures/` incl. the two validation captures 13.1 MB + 47.4 MB, diffs, installed-apk copies, earlier reports).
- The interrupted M20-A restore validation is documented: persistence leg **confirmed** (fitbit record written 23:33:24.7 and verified read-only), restore leg blocked by the app's permission gate (below) — superseded by the new work order; capture stopped at 23:51:53, no hardware actions initiated in this phase.

## 2. Where notification permission touches the connect flow (exact cause)

### Request site — only one
- `app/src/androidMain/kotlin/bluebit/MainActivity.kt:496-512` (`requestBlePermissions()`): requests `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, (pre-S: `ACCESS_FINE_LOCATION`) **and `POST_NOTIFICATIONS` (line 510)** in one `RequestMultiplePermissions` dialog.

### Gate site — the actual cause
- `MainActivity.kt:56-71` (launcher callback):
  ```kotlin
  val allGranted = permissions.entries.all { it.value }          // line 59 — all-or-nothing
  if (denied.isNotEmpty()) Log.w("BlueBit", "PERMISSION_DENIED: $denied. BLE features disabled until granted via Settings.")  // line 63
  if (allGranted) { initializeBluetoothDependentOperations() }    // line 65-66 — ALL-OR-NOTHING
  else { logBluetoothAdapterState() }                             // line 67-70
  ```
- `initializeBluetoothDependentOperations()` (`MainActivity.kt:478-494`) is the **only** caller of `pairedDeviceCoordinator?.restoreOnLaunch()` (line 489).

**Live confirmation (own capture, `captures/m20a_validation_2026-10-25_2340.logcat.txt`):** at the relaunch (pid 26533, started 23:35:44.7): `PERMISSION_STATUS: {BLUETOOTH_SCAN=GRANTED, BLUETOOTH_CONNECT=GRANTED, POST_NOTIFICATIONS=DENIED}` (23:35:45.973) → result `POST_NOTIFICATIONS=false` (23:35:46.929) → `PERMISSION_DENIED … BLE features disabled until granted via Settings` → **no `restoreOnLaunch()` run, zero BlueBit connect attempts to the persisted Inspire 3** (no `PAIRING_START` under the relaunched pid; only unrelated passive adverts). Both hardware sessions showed POST_NOTIFICATIONS denied (first denial at 23:32:43.973 in session 1).

- Nothing else in the app checks `POST_NOTIFICATIONS` (repo-wide grep: single request site, single gate site). `AndroidBleScanner.kt:75-76, 101-102` guards only genuine BLE-critical permissions (`BLUETOOTH_SCAN`/`ACCESS_FINE_LOCATION`) — untouched by notifications and healthy.
- Manual UI connect is **not** notification-gated: `BlueBitAppShell` detail-screen connect (`BlueBitAppShell.kt:190-226`) → `provider.connect()` runs unconditionally; proven live in session 1 (full pairing sequence succeeded with notifications denied).

### Requesting POST_NOTIFICATIONS is itself legitimate
- The manifest declares `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` and a real foreground service `BleDiagnosticLogService` (`connectedDevice` type) that posts its own progress notification (`BleDiagnosticLogger.kt:89`, `BleDiagnosticLogService.kt:81-82, 201-211, 235-236`) for the optional BLE diagnostic logging toggled in Settings. `POST_NOTIFICATIONS` governs that notification's **visibility**/chime — not BLE connectivity, and never the ability to run the foreground service. No background-operation notification must be removed; nothing here bypasses supervision or device policy.

## 3. Is notification permission required for the connection path? — NO
- Android 13+ rule: `POST_NOTIFICATIONS` controls whether *this app's notifications are shown to the user*; it is not a prerequisite for BLE scanning/connecting, calling `BluetoothGatt` APIs, or running a foreground service. Denial only suppresses the app's own notification presentation (e.g. the diagnostics FGS notification in the drawer); the service itself keeps running and stays compliant.
- Live proof: session 1 performed a **complete** user-initiated Inspire 3 connect (GATT connect 23:33:23.368 → discovery → ephemeral write → notify → RESET handshake → `PAIRING_END success=true` 23:33:24.743 → persistence write) **entirely with POST_NOTIFICATIONS denied**.

## 4. Existing in-app connection status surface (what shows today)
- `BlueBitAppShell.kt:158-159`: real `provider.connectionState` collected → `isConnected` (genuine, no fake states).
- `BlueBitAppShell.kt:190-226`: the Connect/Disconnect action exists on the device-detail screen; `isProcessing` prevents double-taps; failures are **only logged** (`CONNECT_FAILED`, line 219) — never shown to the user.
- `DeviceDetailScreen.kt:82` shows `ConnectionIndicator(connected = Boolean)` and the toggle button labels only `Connect`/`Disconnect` (line 117). No `Connecting…`, no `Failed`, no error text, no explicit retry hint. (These additions must not fake success — labels must mirror the real `ConnectionState`.)
- `HomeScreen` receives hard-coded `connected=false` placeholders (BlueBitAppShell.kt:103-114) — pre-existing placeholder, out of scope here.

## 5. Approved-scope plan (Phase 2, minimal)
1. **`MainActivity.kt`** — replace the all-or-nothing callback with a BLE-critical classification: init (incl. `restoreOnLaunch()`) proceeds when the Bluetooth-critical permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, pre-S `ACCESS_FINE_LOCATION`) are granted; `POST_NOTIFICATIONS` denial downgraded to an informational log (visibility-only impact) instead of disabling BLE features. Keep requesting `POST_NOTIFICATIONS` (FGS notification visibility). Keep the real disabled-state warning when a Bluetooth-critical permission is actually denied; no auto re-prompt loop.
2. **Testable decision extraction (new commonMain file)** — `bluebit/core/permissions/DevicePermissionPolicy.kt`: pure classifier (`bluetoothReady(map)`, `deniedBluetoothPermissions(map)`) so the permission-denied path is JVM-testable withoutloading Android classes.
3. **`BlueBitAppShell.kt`** — surface failures in-app: state a `connectionStatusNote` set from the real connect `Result` failure (with a `connectFailureNote()` helper), cleared on success/disconnect; pass `connecting` (real `ConnectionState.Connecting`) + note into the detail screen.
4. **`DeviceDetailScreen.kt`** — add optional (back-compatible) `connecting`/`connectionStatusNote` params; button label `Disconnect`/`Connecting…`/`Connect` (the existing button **is** the retry action — reused, not duplicated); note displayed under the connection indicator. Presentation labels via a pure `connectionStatusLabel()` helper (new commonMain file) for JVM testing.
5. **Tests (desktopTest, offline)** — policy classifier matrix (all granted / notifications denied / scan denied / connect denied / mixed), label mapping, failure-note mapping; then the full existing suite.
6. **Explicitly unchanged:** DTLS/Gattlink/CoAP/GoldenGate, identity resolution, `GattlinkTxSerializer`, `TxSink`, `Stack`, `Bridge`, bond handling/tracker protocol, `BleDiagnosticLogService`+manifest, FitCloud P6 provider behaviour, M20-A paired-event persistence flow, no fake success states anywhere.
