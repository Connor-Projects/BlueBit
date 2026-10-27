# Phase 20 Follow-up — Notification-Free Connection Flow (Implementation, Tests, Build)

**Date:** 2026-10-25 23:52 → 23:58 (workstation clock)
**Companion to:** `PHASE20_FOLLOWUP_NOTIFICATION_GATE_INVESTIGATION.md` (Phase 1 findings, exact cause). This file records Phase 2/3 only.
**Stop conditions respected:** no Bluetooth connect/scan initiated, app not launched, no reinstall, no ADB state changes, no diagnostics/probes, no tracker interaction. The only workflow after the build was APK identity verification (local hash + apksigner/aapt/zip reads).

---

## 1. Changes made (exact paths)

**Production (3 edited files — all were already ` M` in the pre-existing baseline; cumulative diffs saved, my delta itemized in §2):**

| Path | Change |
|---|---|
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/MainActivity.kt` | Permission callback re-classified (notification permission no longer blocks BLE init); `DevicePermissionPolicy` field; idempotency guard for `initializeBluetoothDependentOperations()`; launch-path init when only non-critical permission is pending |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/BlueBitAppShell.kt` | Detail-screen connect failures now set an in-app status note from the real `Result` (cleared on success/disconnect); `connecting` derived from the real `ConnectionState`; both passed into the detail screen |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/core/ui/screens/DeviceDetailScreen.kt` | Optional `connecting` / `connectionStatusNote` params (back-compatible defaults); "Connecting…" text under the connection indicator; error-styled note; toggle button label via `connectionStatusLabel()` (same button reused as Connect/Retry/Disconnect) |

**New commonMain (pure, JVM-testable):**

| Path | Content |
|---|---|
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/core/permissions/DevicePermissionPolicy.kt` | `bluetoothReady(map)` / `deniedBluetoothPermissions(map)` — notification permission never blocks BLE features |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/core/ui/screens/ConnectionPresentation.kt` | `connectionStatusLabel()` / `connectFailureNote()` — honest state labels + failure notes |

**New tests:** `BlueBitApp/app/src/desktopTest/kotlin/bluebit/core/permissions/DevicePermissionPolicyTest.kt` (7 tests), `BlueBitApp/app/src/desktopTest/kotlin/bluebit/core/ui/ConnectionPresentationTest.kt` (6 tests).

## 2. My delta on the three pre-modified files (separation from baseline ` M` hunks)

Cumulative diffs vs HEAD are archived verbatim in `phase20/diffs/`:
`MainActivity.kt.cumulative_after_gatefix.diff` (46,592 B — sha256 `f03f47b8513000f6…`), `BlueBitAppShell.kt.cumulative_after_gatefix.diff` (16,048 B — `67ee6ec34c5d33c1…`), `DeviceDetailScreen.kt.cumulative_before/after_gatefix`: `a5b8fe9151ec3c1a…` (8,718 B).

My MainActivity deltas = exactly:
1. import `bluebit.core.permissions.DevicePermissionPolicy`;
2. callback body: `allGranted` gate replaced by `deniedBluetoothPermissions` (Bluetooth-critical only) + `NOTIFICATIONS_OPTIONAL` info log + same-style `PERMISSION_DENIED` warning (now only for genuinely BLE-critical denials) + init when Bluetooth-ready;
3. new fields: `permissionPolicy` (Bluetooth-critical set incl. pre-S fine-location) and `bluetoothOperationsInitialized`;
4. `initializeBluetoothDependentOperations()` guard (single run per instance — prevents double-run from callback + launch-path);
5. `requestBlePermissions()`: when Bluetooth-critical are already granted and only a non-critical permission is pending, init runs immediately, still followed by the (non-blocking) request.

BlueBitAppShell delta = `connectionStatusNote` state + `connecting` derivation + note set/clear in the toggle handler + two params passed + `connectFailureNote` import. DeviceDetailScreen delta = two optional params + "Connecting…"/note Texts + label rewrite via helper.

**Unchanged by design:** DTLS, Gattlink, CoAP, GoldenGate, identity resolution, GATT bond handling, `GattlinkTxSerializer`, `TxSink`, `Stack`, `Bridge`, M20-A paired-event persistence, scanner internals (`AndroidBleScanner` keeps its own genuine BLE guards), `BleDiagnosticLogService` + manifest (FGS + its notification untouched — Android compliance kept), FitCloud P6 behaviour (shared UI/gate changes only, no provider logic touched). No fake success states: `connected` still mirrors the real provider `connectionState`.

## 3. Tests (all offline, `--offline`, JDK 17)

| Suite | Command / scope | Result |
|---|---|---|
| New: permission policy | `bluebit.core.permissions.DevicePermissionPolicyTest` | **7/7 pass** (matrix: all granted; notifications-denied-only ready; scan/connect/missing/both denied; legacy fine-location) |
| New: presentation | `bluebit.core.ui.screens.ConnectionPresentationTest` | **6/6 pass** (Connect/Connecting…/Disconnect precedence; SecurityException note; message+retry note; no-message fallbacks) |
| Full desktop suite | `:app:desktopTest --offline` | **114 tests / 16 classes, 0 failures, 0 errors**, exit 0 (101 previous + 13 new) |
| Phase 17 JVM harness | init-script run | **21 tests, 0 failures**, exit 0 |
| Phase 18 replay | init-script run | **7 tests, 0 failures**, exit 0 |
| Phase 17 Python harness | `python run_all.py` | `Ran 54 tests … OK`, exit 0 |

## 4. Build result

- Command: `.\gradlew.bat :app:assembleDebug --offline` → `BUILD SUCCESSFUL in 13s`, exit 0 (only pre-existing deprecation warnings + recurring benign `package.xml` SDK-read-only warnings).
- **APK:** `G:\Dev\BlueBit\BlueBitApp\app\build\outputs\apk\debug\app-debug.apk`
- **Size:** 27,462,589 bytes, mtime 2026-10-25 23:58:07
- **SHA-256:** `72117afb0e7e86a6e4322c607ec7a629db6ea16e9f296df02dc43135120e796f`
- `apksigner verify --print-certs`: `cbfec86d18426835f4f9966eae0a23055b2c10cb43db8dca5e3355751d3374e2` — unchanged signing cert; `aapt badging`: `versionCode='1' versionName='1.0'` → in-place-upgrade compatible. **NOT installed** (reinstall prohibited this round).
- Dex provenance in the built APK: `NOTIFICATIONS_OPTIONAL` ×2, `bluetoothReady` ×2, `connectionStatusLabel` ×1, `connectFailureNote` ×2; M20-A markers retained (`getPairedDeviceEvents` ×4, `transientErrorCount` ×3).

## 5. Git state

- Same 3 files move from ` M` (pre-existing) to ` M` (pre-existing + this follow-up's deltas); new untracked: `core/permissions/`, `core/ui/screens/ConnectionPresentation.kt`, `desktopTest/kotlin/bluebit/core/` (both new test classes).
- Porcelain: **80 → 83 entries** (+3, all `??` accounted above); 0 stashes; HEAD unchanged; no commits/stashes/resets.
- New phase20 artefacts: this report, the investigation report, three cumulative diff files — nothing overwritten.

## 6. Expected behaviour after this change (for the next approved hardware leg)

- With `POST_NOTIFICATIONS` denied (current device state): app launch → `PERMISSION_STATUS` → (Bluetooth-critical already granted) → **`init BLE ops immediately` → `restoreOnLaunch()` runs** → coordinator tries to restore the persisted P6 (pre-existing shared behaviour) and the persisted Inspire 3 (`PAIRING_START … D8:9E:C1:BA:3D:E4` without any user press; reconnect attempt vs confirmed success distinguishable via `PAIRING_END success=true`).
- In-app detail screen now shows Connecting… / Connected / failure note with retry (button reused); `NOTIFICATIONS_OPTIONAL` explains the notification-permission impact honestly (visibility only).
- The diagnostics FGS notification remains invisible until notification permission is granted (no behaviour change), and foreground-service notification requirements were not tampered with.

## 7. Limitations / not covered

1. `MainActivity`/Compose flows themselves are not JVM-testable; the decision logic was extracted to pure helpers and tested — the Activity wiring is covered only by compile + dex provenance (and the next hardware inspection).
2. No `androidTest`/UI-instrumentation tests exist in this repo; "permission-denied UI path" is covered by the policy classifier + presentation helpers, not by an instrumented screenshot test.
3. The `WRITE_EXTERNAL_STORAGE` (pre-Q) request remains non-critical like POST_NOTIFICATIONS — it cannot block BLE init; not exercised on the Android-16 test device.
4. Home-screen placeholder values (hard-coded `connected=false`) remain as before — pre-existing placeholder UI, out of scope.
