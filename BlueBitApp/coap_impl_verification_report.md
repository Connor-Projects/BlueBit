# CoAP Read-Only Probe — Verification Report

**Date:** 2026-10-10  
**Scope:** Latest CoAP read-only diagnostic implementation in `BlueBitApp`

## Summary

The CoAP read-only diagnostic implementation was verified against the eight requested checks. One architecture defect was found and fixed: `CoapReadOnlyResult` lived in the Fitbit-specific package `bluebit.fitbit.diagnostics` and was imported by the provider-neutral core UI screen `DeviceDetailScreen`. The data class was moved to `bluebit.core.diagnostics` and all callers were updated. All builds and tests pass.

## Check Results

| # | Check | Result | Notes |
|---|-------|--------|-------|
| 1 | Device-detail card displays the actual latest `CoapReadOnlyResult` | ✅ PASS | `MainActivity` updates `lastCoapReadOnlyResult` when the diagnostic action completes; the value flows through `BlueBitAppShell` to `DeviceDetailScreen` and is rendered by `CoapReadOnlyResultCard`. |
| 2 | Error, timeout and empty-result states are visually distinguishable | ✅ PASS | `CoapReadOnlyResultCard` renders `error` in `MaterialTheme.colorScheme.error` (red). Timeouts and empty bodies are converted to `coapSuccess=false` with an `error` string by `CoapReadOnlyProbeLogic`. |
| 3 | UI does not imply CoAP resource discovery succeeded | ✅ PASS | Card title is neutral: "Last CoAP Read-Only Result". It displays raw booleans and values; no celebratory or success-only wording. |
| 4 | Default path remains `.well-known/core` | ✅ PASS | `CoapReadOnlyProbe.runProbe` and `CoapReadOnlyProbeLogic.createRequestSpec` both default to `.well-known/core`. |
| 5 | No automatic probe runs on connection, navigation, or screen launch | ✅ PASS | The probe is invoked only from the `"Probe CoAP Read-Only"` `ProviderDiagnostic` button action in `MainActivity`. No `LaunchedEffect`/auto-trigger exists in `DeviceDetailScreen` or `BlueBitAppShell`. |
| 6 | No unverified path is selectable in the UI | ✅ PASS | Only one button exists; no path selector or text field exposes the optional `path` parameter. |
| 7 | Provider-neutral core modules contain no Fitbit-specific protocol imports | ✅ FIXED | `DeviceDetailScreen` previously imported `bluebit.fitbit.diagnostics.CoapReadOnlyResult`. `CoapReadOnlyResult` was moved to `bluebit.core.diagnostics`; all imports updated. Post-fix search found no `bluebit.fitbit` or `com.fitbit` imports under `bluebit.core.*`. |
| 8 | Tests cover path validation, response code formatting, timeout, cleanup | ✅ PASS | 15 desktop tests exercise default path, leading-slash stripping, blank path rejection, query-string rejection, response-code formatting, success/error/empty/4.04 responses, truncation, secret redaction, malformed link-format, timeout result, and cleanup failure swallowing. |

## Defect Fixed

**Issue:** `bluebit.core.ui.screens.DeviceDetailScreen` imported `bluebit.fitbit.diagnostics.CoapReadOnlyResult`, coupling a provider-neutral core screen to a Fitbit-specific diagnostic package.

**Fix:**
- Created `app/src/commonMain/kotlin/bluebit/core/diagnostics/CoapReadOnlyResult.kt` containing the `CoapReadOnlyResult` data class.
- Removed the class from `bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt` and added an import from the new core package.
- Updated imports in:
  - `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt`
  - `app/src/androidMain/kotlin/bluebit/MainActivity.kt`
  - `app/src/commonMain/kotlin/bluebit/BlueBitAppShell.kt`
  - `app/src/commonMain/kotlin/bluebit/core/ui/screens/DeviceDetailScreen.kt`
  - `app/src/desktopTest/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeTest.kt`

No DTLS, bonding, authentication, provider behaviour, or UI design was changed.

## Build / Test Results

Commands run from `G:\Dev\BlueBit\BlueBitApp`:

```
cmd /c gradlew.bat :app:desktopTest
cmd /c gradlew.bat :app:compileDebugKotlinAndroid
```

| Task | Result |
|------|--------|
| `:app:desktopTest` | ✅ BUILD SUCCESSFUL — 6 actionable tasks; CoAP tests passed. |
| `:app:compileDebugKotlinAndroid` | ✅ BUILD SUCCESSFUL — 15 actionable tasks; only pre-existing deprecation warnings. |

## Constraints Observed

- No CoAP or BLE traffic was sent to a watch.
- DTLS, bonding, authentication, and provider behaviour were not modified.
- UI was not redesigned; only the import path of a displayed data class changed.
- No unverified paths were added or probed.
