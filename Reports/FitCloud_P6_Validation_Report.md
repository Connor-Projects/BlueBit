# BlueBit — FitCloud P6 SDK Validation Report

**Date:** 2026-10-29  
**Project:** `G:\Dev\BlueBit`  
**Objective:** Controlled compatibility validation of the FitCloudPro Android SDK against the actual FitCloud P6 smartwatch (per `Reports/FitCloud_SDK_Compatibility_Report.md`, Section 8).

---

## Test Environment

| Item | Value |
|------|-------|
| Development device | Samsung Galaxy A17 4G (Android 14) |
| Build variant | `app:assembleDebug` (debug APK) |
| SDK version | `com.topstep.wearkit:sdk-base:3.0.2.4`, `sdk-fitcloud:3.0.2.4` (unchanged) |
| Maven repo | `http://120.78.153.20:8081/repository/maven-public/` (HTTP, insecure) — resolved |
| P6 device name | `P6_` |
| P6 recorded MAC | `90:EB:99:D6:5F:99` |
| P6 company ID | `0x5A54` (23124) |
| P6 advertised service | `00003802-0000-1000-8000-00805F9B34FB` (`0x3802`) |

---

## Implementation Summary

The following read-only diagnostic functionality was implemented and wired into the app. The **debug build compiles successfully** (`:app:assembleDebug` BUILD SUCCESSFUL) and the desktop test suite passes (`:app:desktopTest` BUILD SUCCESSFUL).

### New files

| File | Purpose |
|------|---------|
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitcloud/diagnostics/GattDiscoveryDiagnostic.kt` | Read-only P6 GATT discovery diagnostic. Connects, discovers services, enumerates the full GATT database (services, characteristics with properties/permissions, descriptors with permissions), logs to logcat (`BlueBitP6Gatt`), and writes a timestamped report to the app's external files dir. Never writes to characteristics or enables notifications. |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/core/diagnostics/DiagnosticLog.kt` | In-memory timestamped developer diagnostic log (INFO/DEBUG/WARNING/ERROR), exportable/copyable/clearable. |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/core/ui/screens/Screen.kt` | Added `DeveloperDiagnostics` route and `DiagnosticAction` data class. |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/core/ui/screens/DeveloperDiagnosticsScreen.kt` | Developer Diagnostics UI screen with master Diagnostic Mode switch and categorized diagnostic buttons (Bluetooth, FitCloud SDK, Protocol, System). Unavailable actions are disabled and clearly marked, not faked. |

### Modified files

| File | Change |
|------|--------|
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitcloud/AndroidFitCloudProvider.kt` | `dump_gatt` diagnostic now invokes `GattDiscoveryDiagnostic.connectAndDiscover()` (read-only) and writes a report file. |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/MainActivity.kt` | Diagnostic Mode preference (persisted via SharedPreferences), diagnostic action list, and `developerDiagnosticsContent` passed to `BlueBitAppShell`. |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/BlueBitAppShell.kt` | Accepts `diagnosticModeEnabled`, `onDiagnosticModeToggle`, `diagnosticActions`, `developerDiagnosticsContent`; routes `Screen.DeveloperDiagnostics`. |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/core/ui/screens/SettingsScreen.kt` | Added Diagnostic Mode switch + "Developer Diagnostics" button (enabled only when Diagnostic Mode is ON). |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/core/ui/components/PrimaryButton.kt` | Added `enabled` parameter. |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/core/ui/components/SecondaryButton.kt` | Added `enabled` parameter. |

### Diagnostic Mode behavior (Phase 7 acceptance criteria)

1. Defaults to OFF (persisted across restarts).
2. Turning ON exposes the Developer Diagnostics section.
3. Turning OFF hides/disables diagnostic controls; normal functionality unaffected.
4. Normal wearable functionality continues working (Fitbit Inspire 3 untouched).
5. Every available diagnostic button invokes a real implemented operation.
6. Unsupported/unavailable actions are disabled and labelled `(unavailable)`, not faked.
7. Diagnostic output is timestamped and copyable/exportable.
8. Existing Fitbit Inspire 3 functionality remains untouched.
9. Existing P6 research and HCI captures remain untouched.
10. Android debug build succeeds.

---

## Phase 1 — Repository and Device Readiness

| Check | Status | Notes |
|-------|--------|-------|
| SDK initialisation code present | ✅ | `FitCloudSdkInit.kt` builds `FcSDK` singleton; wired in `MainActivity.onCreate`. |
| Debug build installs and launches | ✅ | `:app:assembleDebug` BUILD SUCCESSFUL. |
| Bluetooth permissions on Android 14 | ✅ | Manifest declares `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`; runtime permission flow in `MainActivity`. |
| Scanner can identify P6 | ✅ Verified | `FitCloudProvider.canHandle()` matches `0x3802`; SDK `FcScanner` accepts companyId `0x5A54`. P6 discovered and identified. |
| Existing diagnostic functionality reusable | ✅ | `FitbitGattDiagnostic` pattern reused for `GattDiscoveryDiagnostic`; `BleDiagnosticLogger`/watchdog available. |

P6 bond/pairing state: **paired** with official FitCloud app (userId `26544037`). Our SDK test uses login path (`bindOrLogin=false`) with matching userId.

---

## Phase 2 — P6 GATT Discovery

**Status: COMPLETE (physical device tested).**

Executed on 2026-10-29 against physical P6 (MAC `90:EB:99:D6:5F:99`, Bond State: NONE initially, then paired):

- **Connection:** Raw GATT connect succeeded without bonding
- **Service discovery:** Succeeded — **5 services discovered**
- **Critical finding:** The advertised service `0x3802` does **not** appear in the connected GATT database — it is advertisement-only (or exposed only after bonding)
- **Output:** `Reports/FitCloud_P6_GATT_Discovery.md` + raw dumps `p6_gatt_dump_20261029_221935.txt` and `p6_gatt_dump_20261029_222023.txt`

Full service map documented in `Reports/FitCloud_P6_GATT_Discovery.md`. Notify-capable characteristics: `ff14` (svc `ff12`), `ff03` (svc `01ff-...`), `6487` (svc `6287-...`). All characteristic permissions `NONE(0x0)` (no encryption required).

---

## Phase 3 — SDK Connection Validation

**Status: COMPLETE (physical device tested).**

Executed on 2026-10-29 against paired P6 (MAC `90:EB:99:D6:5F:99`, paired with official FitCloud app under userId `26544037`):

| Test | Result | Details |
|------|--------|---------|
| SDK `connect(bindOrLogin=false)` | ✅ **SUCCESS** | Connector reached `CONNECTED` state |
| User profile | `userId=26544037`, `sex=true`, `age=11`, `height=170f`, `weight=58.2f` | Matches official app binding |
| Connection timestamp | 2026-10-29 22:57:45 | Logged via `Fc#Connector` |
| `FcConnectorState` transitions | `DISCONNECTED` → `CONNECTING` → `CONNECTED` | |
| `FcConnectorError` | None | `doLoginAuth result:0`, `Auth login:mu5z25th` |
| Duration | ~2 seconds | From connect call to CONNECTED |

**Key finding:** The P6 must be bound to the official FitCloud app first. The SDK requires the same `userId` for login. Our test initially failed with hardcoded `bluebit_user`; succeeded when updated to `26544037`.

---

## Phase 4 — Read-Only SDK Queries

**Status: COMPLETE (physical device tested).**

All queries executed against CONNECTED SDK session:

| Query | Result | Response |
|-------|--------|----------|
| `getDeviceInfo()` | ✅ **SUCCESS** | Project: `0000000052B6`, Patch: `0`, Flash: `0`, App: `101`, IC type: `IC_8762C` |
| `requestLcdShape()` | ❌ **FAILED** | Consistently fails with "unknown error" (SDK-device mismatch) |
| `requestDialPushInfo()` | ✅ **SUCCESS** | Dial num: `19`, bin ver: `1`, position: `0`, tool: `1.5`, spaces: `0` |
| `requestUiInfo()` | ✅ **SUCCESS** | UI num: `3661`, serial: `1`, style: `19` |

**3 of 4 queries succeed.** LCD shape query fails consistently — likely the P6 doesn't support `requestLcdShape()` or the SDK implementation is incompatible with this device variant.

---

## Phase 5 — Evidence Collection

**Status: COMPLETE.**

- **GATT discovery logcat** captured under tag `BlueBitP6Gatt` (files: `p6_gatt_dump_20261029_221935.txt`, `p6_gatt_dump_20261029_222023.txt`)
- **SDK connector state transitions** captured under tag `Fc#Connector` and `Fc#AuthOperation`
- **Query results** captured in `DiagnosticLog` export (see below)
- **HCI snoop** — attempted; Samsung Galaxy A17 reverts `bluetooth_btsnoop_default_mode` to `empty` on reboot; not available on this device
- All captures saved to `Reports/` without overwriting previous captures

### DiagnosticLog Export (full)

```
BlueBit Developer Diagnostics Log
Exported: 2026-10-29 22:59:56
Total entries: 21

[22:54:59.81] [INFO] FitCloud SDK Connect -> OK: CONNECTED: addr=90:EB:99:D6:5F:99, sdk=3.0.2.4, bindOrLogin=false
[22:55:22.96] [INFO] FitCloud Query Device Info -> OK: Project: 0000000052B6; Patch: 000000000000; Flash: 00000000; App: 00000101; IC type: IC_8762C
[22:55:23.40] [ERROR] FitCloud Query LCD Shape -> FAILED: unknown error
[22:55:24.02] [INFO] FitCloud Query Dial Info -> OK: Current dial num: 19; Current bin version: 1; Current position: 0; Tool version: 1.5; Dial spaces: 0
[22:55:24.55] [INFO] FitCloud Query UI Info -> OK: UI num: 3661, serial: 1, style: 19
... (repeated for subsequent test runs)
```

---

## Phase 6 — Compatibility Decision

**Result: B — Partial Compatibility.**

**Evidence summary:**

| Criterion | Status |
|-----------|--------|
| SDK connects to P6 (with matching userId) | ✅ |
| Device info query returns valid data | ✅ |
| Dial/watch-face info query returns valid data | ✅ |
| UI info query returns valid data | ✅ |
| LCD shape query works | ❌ (fails consistently) |
| No firmware updates / destructive operations | ✅ |

The FitCloudPro SDK **can communicate with the FitCloud P6** for core operations (device identity, watch face state, UI state). The LCD shape query failure is a **partial feature gap** — likely the P6's IC (`IC_8762C`) doesn't implement that SDK endpoint, or the SDK version doesn't support this specific variant.

**This is not protocol incompatibility** — the SDK successfully connects and exchanges data. The SDK-assisted integration strategy (B) from the compatibility report is validated.

---

## Recommended Next Development Milestone

1. **Integrate `testConnectLogin` + read-only queries** into `AndroidFitCloudProvider` capabilities for production use.
2. **Document the LCD shape limitation** — fallback to `requestDialPushInfo().shape` for display dimensions.
3. **Add `FitCloudP6Adapter`** wrapping the SDK with P6-specific quirks (userId mapping, LCD shape fallback).
4. **Implement health data sync** via `FcDataFeature.syncData()` and `FcDataFeature.observerSyncState()`.
5. **Test notifications/messages** via `FcMessageFeature` once P6 is paired with phone.

---

## Files Changed (this session)

- Added: `Reports/FitCloud_SDK_Compatibility_Report.md` (compatibility investigation)
- Added: `Reports/FitCloud_P6_Validation_Report.md` (this report)
- Added: `Reports/FitCloud_P6_GATT_Discovery.md` (GATT service map)
- Added: `Reports/p6_gatt_dump_20261029_221935.txt`, `Reports/p6_gatt_dump_20261029_222023.txt` (raw dumps)
- Modified: `Reports/INDEX.md` (index entries for all reports)
- Added source: `GattDiscoveryDiagnostic.kt`, `DiagnosticLog.kt`, `DeveloperDiagnosticsScreen.kt` (androidMain), `Screen.kt` (DiagnosticAction + route)
- Modified source: `AndroidFitCloudProvider.kt`, `MainActivity.kt`, `BlueBitAppShell.kt`, `SettingsScreen.kt`, `PrimaryButton.kt`, `SecondaryButton.kt`

Build verification: `:app:assembleDebug` BUILD SUCCESSFUL; `:app:desktopTest` BUILD SUCCESSFUL; APK installed on device R5GL62WS7QY.

*End of report.*