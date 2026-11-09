# BlueBit — FitCloudPro SDK Compatibility Investigation (FitCloud P6)

**Date:** 2026-10-29  
**Project:** `G:\Dev\BlueBit`  
**Primary device:** Samsung Galaxy A17 4G  
**Immediate priority:** FitCloud P6 development (Fitbit Inspire 3 on hold)  
**Status:** Investigation complete — recommendation below

---

## 1. Executive Summary and Recommended Integration Strategy

**Recommended strategy: B. SDK-assisted integration.**

The public FitCloudPro Android SDK (`com.topstep.wearkit:sdk-base` + `sdk-fitcloud`, v3.0.2.4 currently wired in BlueBit; latest release 3.0.2.8) is already integrated into BlueBit and builds successfully. It exposes high-level operations (connect/bind/login, device info, settings, health data sync, notifications, DFU/firmware update, watch-face) via the `FcConnector` API. However:

- The SDK **does not explicitly name the FitCloud P6** anywhere in its repository, README, changelog, sample app, or Dokka documentation.
- The SDK's **scanner filters devices by manufacturer/companyId** (`0x5448`, `0x4254`, `0x5A54`), and the P6's observed companyId `0x5A54` (23124) **does match one of these**, which is supportive but not conclusive evidence of P6 support.
- The SDK is **distributed as precompiled AARs**, with protocol internals (`CmdPacket`, `KeyPacket`, `TransportPacket`) marked `@RestrictTo(LIBRARY)` — no source code, no documented BLE service/characteristic UUIDs, no device model capability table.
- Matching the advertised service UUID `0x3802` alone **does not prove protocol compatibility** with the SDK's internal wire protocol.

**Conclusion:** The SDK is a credible candidate and is already partially wired into BlueBit (`AndroidFitCloudProvider`, `FitCloudProvider`, `FitCloudDialInspector`, `FitCloudSdkBleConnection`, `FitCloudSdkInit`). It should be treated as **SDK-assisted integration (B)**: BlueBit should keep its dedicated P6 adapter layer (`FitCloudProvider`/`AndroidFitCloudProvider`) rather than exposing raw SDK commands across the codebase, and validate connectivity against the real P6 before relying on SDK-mediated operations.

No production changes should be made until a controlled validation (Section 8) is performed and documented.

---

## 2. Repository URLs and Exact Versions Inspected

| Item | Value |
|------|-------|
| Android SDK repo | https://github.com/htangsmart/FitCloudPro-SDK-Android |
| Android SDK commit inspected | `fcfe5f6` ("Update doc"), shallow clone, 2026-10-29 |
| Android SDK latest README version | v3.0.2.7 (2026-09-17); changelog lists through v3.0.2.7 |
| iOS SDK repo | https://github.com/htangsmart/FitCloudPro-SDK-iOS |
| iOS SDK commit inspected | shallow clone, 2026-10-29 (checkout partially failed on Windows due to colon-in-filename; main kits extracted) |
| iOS SDK latest version | FitCloudKit pod v1.3.2 (2026.10.28); FitCloudDFUKit v1.3.3 |
| Maven coordinates (Android) | `com.topstep.wearkit:sdk-base:3.0.2.4`, `com.topstep.wearkit:sdk-fitcloud:3.0.2.4` |
| Maven repository | `http://120.78.153.20:8081/repository/maven-public/` (HTTP, insecure) |
| Latest release in Maven metadata | `3.0.2.8` (release), `3.0.2.8-UGREEN-SNAPSHOT` (latest snapshot) |
| BlueBit wired version | `3.0.2.4` (in `BlueBitApp/app/build.gradle.kts`) |
| BlueBit P6 device under test | Name `P6_`, MAC `90:EB:99:D6:5F:99`, service UUID `0x3802`, companyId `23124` (0x5A54) |

---

## 3. License and Dependency Assessment

### Android SDK

- **License:** No LICENSE file present in the repository. Treated as **proprietary** (all rights reserved by vendor htangsmart/Topstep, Shenzhen, China). Redistribution rights are not explicitly granted.
- **Packaging:** Precompiled AARs only (`sdk-base-v3.0.2.7.aar`, `sdk-fitcloud-v3.0.2.7.aar` in repo `libs/`; Realtek extension AARs in `libs/ext/`). No source code, no Javadoc/Dokka source jars in the public repo (Dokka HTML docs are present).
- **minSdkVersion:** `21` for both `sdk-base` and `sdk-fitcloud` (verified from extracted AAR `AndroidManifest.xml`). BlueBit `minSdk = 26` — compatible.
- **Transitive dependencies:** RxAndroidBle (`com.polidea.rxandroidble3`), RxJava3, Timber, Realtek extension AARs (`ext-realtek-bbpro`, `ext-realtek-dfu`, `ext-realtek-file`, `ext-sensorgame`, `sdk-aliagent`, `sdk-fitcloud-compat`). BlueBit already excludes `ext-realtek-bbpro` and `ext-realtek-file` to avoid duplicate-class issues.
- **Repository:** Private Chinese Maven over **HTTP** (`allowInsecureProtocol = true`). Security/trust concern; already isolated in BlueBit's `settings.gradle.kts`.
- **Maven resolvability:** Verified reachable — `maven-metadata.xml` returns `3.0.2.8` release and `3.0.2.4` is listed. `sdk-base` and `sdk-fitcloud` `3.0.2.4` are downloadable/resolvable.

### iOS SDK

- **License:** MIT License, copyright (c) 2017 pcjbird — different copyright holder than the Android SDK; treat as a separate codebase.
- **Packaging:** Precompiled xcframeworks (FitCloudKit, FitCloudDFUKit, FitCloudWFKit, FitCloudNWFKit). No source.
- **Chip support:** Realtek | Nordic.
- Relevance: supplementary reference only; no protocol constants usable for Android integration.

---

## 4. Relevant SDK Classes, Modules, Sample Files, and API Entry Points (with paths)

All paths below are relative to the inspected Android SDK repository clone at `/tmp/sdk-android`, except where noted as AAR-internal.

### SDK modules (Dokka docs)

| Module | Package | Purpose |
|--------|---------|---------|
| `sdk-base` | `com.topstep.wearkit.base.*` | BLE primitives, connector states, auth, error handling, bluetooth helper, process lifecycle |
| `sdk-fitcloud` | `com.topstep.fitcloud.sdk.*` | FitCloud-specific connection abstractions, features, DFU, protocol, scanner |

### Public API entry points (from Dokka docs + sample app)

| Class / member | Package | Role |
|----------------|---------|------|
| `FcSDK` (interface) | `com.topstep.fitcloud.sdk.v2` | FitCloudSDK entry class; Builder(application, processLifecycleObserver) |
| `FcSDK.Builder` | `com.topstep.fitcloud.sdk.v2` | Builds FcSDK singleton; `.setTestStrictMode()`, `.setBuiltInFeatures()` |
| `FcSDK.connector` | `com.topstep.fitcloud.sdk.v2` | `FcConnector` — connect/control device (most important class) |
| `FcConnector.connect(BluetoothDevice, userId, bindOrLogin, sex, age, height, weight)` | `com.topstep.fitcloud.sdk.v2` | Connect with bind/login mode |
| `FcConnector.close()` / `disconnect()` / `reconnect()` | `com.topstep.fitcloud.sdk.v2` | Connection lifecycle |
| `FcConnector.observerConnectorState()` | `com.topstep.fitcloud.sdk.v2` | Observable<FcConnectorState> |
| `FcConnector.configFeature()` | `com.topstep.fitcloud.sdk.v2` | `FcConfigFeature` — device info, function config |
| `FcConnector.settingsFeature()` | `com.topstep.fitcloud.sdk.v2` | `FcSettingsFeature` — settings, dial info, UI info, LCD shape |
| `FcConnector.dataFeature()` | `com.topstep.fitcloud.sdk.v2` | `FcDataFeature` — health/sync data |
| `FcConnector.messageFeature()` | `com.topstep.fitcloud.sdk.v2` | `FcMessageFeature` — notifications/messages |
| `FcConnector.newDfuManager()` | `com.topstep.fitcloud.sdk.v2` | `FcDfuManager` — firmware/dial/UI DFU |
| `FcConnector.getDevice()` / `getConnectorState()` / `isBindOrLogin()` | `com.topstep.fitcloud.sdk.v2` | Connection introspection |
| `FcAuthMode` / `FcAutoReconnectMode` / `FcConnectorState` / `FcConnectorError` | `com.topstep.fitcloud.sdk.connector` | Auth (AUTO/BIND/LOGIN), reconnect, state, error |
| `FcScanner.scan(...)` | `com.topstep.fitcloud.sdk.scanner` | Scans and filters FitCloud devices by companyId |
| `FcScanResult` (= `ScanResult`) | `com.topstep.wearkit.base.scanner` | Scan result typealias |
| `FcDeviceInfo` / `FcDeviceInfo.Feature` (150+ flags) | `com.topstep.fitcloud.sdk.v2.model.config` | Device identity + capability bitmask |
| `CmdPacket`, `KeyPacket`, `TransportPacket` | `com.topstep.fitcloud.sdk.internal.protocol` | **Internal** (`@RestrictTo(LIBRARY)`) protocol framing |
| `FcBuiltInFeatures` | `com.topstep.fitcloud.sdk.v2.features` | autoSetLanguage, mediaControl, musicControl, telephonyControl, GPS hot start |
| `FcDialPushInfo`, `FcDialSpace`, `FcUiInfo`, `FcLcdShape`/`FcShape` | `...model.settings.dial`, `...model.settings` | Watch-face query models |

### Sample application (reference usage)

| Path | Purpose |
|------|---------|
| `sample/app/src/main/java/com/topstep/fitcloud/sample2/FitCloudSDKInit.kt` | SDK init pattern (Timber + RxAndroidBle logging + FcSDK.Builder + RxJava error handler) |
| `sample/app/src/main/java/com/topstep/fitcloud/sample2/data/device/DeviceManager.kt` | Ground-truth connect call: `connector.connect(address, userId, bindOrLogin, sex, age, height, weight)`; bind vs login semantics; `isBindOrLogin()` |
| `sample/app/build.gradle.kts` | Dependency wiring pattern (excludes realtek modules) |
| `sample/app/libs/lib-toolkit-v1.0.3.aar` | Sample-bundled toolkit AAR |

### BlueBit existing integration (already wired, builds successfully)

| Path | Role |
|------|------|
| `BlueBitApp/app/build.gradle.kts` | Declares `sdk-base:3.0.2.4` + `sdk-fitcloud:3.0.2.4` with realtek exclusions |
| `BlueBitApp/settings.gradle.kts` | Maven repo `http://120.78.153.20:8081/repository/maven-public/` (insecure) |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitcloud/FitCloudSdkInit.kt` | FcSDK singleton init, RxAndroidBle logging, RxJava error handler |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitcloud/AndroidFitCloudProvider.kt` | `WearableProvider` impl using `FcConnector`; connect (bindOrLogin=true) / reconnect (bindOrLogin=false) |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitcloud/FitCloudSdkBleConnection.kt` | Stub `BleConnection` (no-op) — GATT managed by SDK |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitcloud/FitCloudDialInspector.kt` | Read-only device/dial info queries |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitcloud/FitCloudProvider.kt` | Common `WearableProvider` stub (`connect()` = `NotImplementedError`) |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitcloud/FitCloudUuids.kt` | `FITCLOUD_PRIMARY_SERVICE_UUID = 00003802-...` |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/MainActivity.kt` | Wires `AndroidFitCloudProvider(this, fcSDK)` into `ProviderRegistry` alongside `FitbitProvider` |

---

## 5. Evidence For and Against P6 Compatibility

### Evidence FOR

1. **CompanyId match:** The SDK's `FcScanner` filters FitCloud devices by companyId `0x5448`, `0x4254`, `0x5A54` (documented in `FcScanner` Dokka). The P6's observed manufacturer companyId is `23124` = `0x5A54` — an exact match to one of the SDK's three accepted IDs.
2. **Service UUID alignment:** BlueBit's `FitCloudProvider.canHandle()` matches scan results advertising `0x3802`; this is the SDK's expected primary service discriminator (per `FitCloud_Protocol_Findings.md`).
3. **SDK already integrated and building:** BlueBit compiles `:app:assembleDebug` successfully with `sdk-fitcloud:3.0.2.4`; `AndroidFitCloudProvider` is wired into `ProviderRegistry` and `MainActivity`.
4. **Broad device-family target:** The SDK targets "FitCloud" / "FlyWear" devices generically (Realtek + Nordic chip support per iOS README), not one single model — the P6 is a FitCloud-family device.
5. **Read-only queries already exercised:** `FitCloudDialInspector.queryDeviceInfo()` / `queryDialInfo()` use public SDK APIs (`getDeviceInfo()`, `requestDialPushInfo()`, `requestUiInfo()`, `requestLcdShape()`) that would return P6-specific data when connected.

### Evidence AGAINST / limitations

1. **No explicit P6 mention:** Zero references to "P6", "FitCloud P6", or "0x3802" in the Android SDK repository (README, changelog, sample, Dokka docs, `libs/` AARs). The SDK does not publish a device compatibility table or model-specific capability checks.
2. **No documented BLE UUIDs:** The SDK does not expose service/characteristic UUIDs publicly; protocol framing (`CmdPacket`/`KeyPacket`/`TransportPacket`) is `@RestrictTo(LIBRARY)`. Matching `0x3802` on the advertisement does **not** prove the SDK's GATT-level wire protocol matches the P6's observed characteristics.
3. **Precompiled AARs only:** No source code; cannot verify the exact GATT characteristic map the SDK uses against BlueBit's pending P6 GATT dump (which remains unknown per `FitCloud_Protocol_Findings.md`).
4. **Authentication model:** `connector.connect(..., bindOrLogin, ...)` requires `userId`, sex, age, height, weight and performs bind-or-login against FitCloud's service. The P6 may require prior binding via the official app (documented risk in `FitCloud_Refactor_Report.md`). BlueBit's P6 is "already bound via BlueBit" per the WatchFace report, but SDK bind semantics are unconfirmed for P6.
5. **SDK version drift:** BlueBit is on `3.0.2.4`; latest release is `3.0.2.8`. Changelog for `3.0.2.5`–`3.0.2.8` is not detailed in the README (only up to `3.0.2.7` listed with brief notes). P6-specific fixes may exist in intermediate versions.

**Net:** CompanyId `0x5A54` match + generic FitCloud targeting + successful build = **supportive but not conclusive**. Direct P6 compatibility has not been established by the SDK repository alone.

---

## 6. Comparison with BlueBit's Existing `0x3802` Observations

From `FitCloud_Protocol_Findings.md` (P6_, MAC 90:EB:99:D6:5F:99):

| Observation | Value | SDK correspondence |
|-------------|-------|-------------------|
| Advertised primary service UUID | `00003802-0000-1000-8000-00805F9B34FB` (`0x3802`) | `FitCloudProvider.canHandle()` matches this exact UUID; `FITCLOUD_PRIMARY_SERVICE_UUID` constant |
| Manufacturer companyId | `23124` (`0x5A54`) | SDK `FcScanner` accepts `0x5A54` among `0x5448`/`0x4254`/`0x5A54` |
| Advertising behavior | Strong when unpaired; stops after bonding via official app | Consistent with SDK bind/login model; SDK `connect(bindOrLogin=true)` may re-bind |
| Full service tree | ❌ Unknown (no GATT dump yet) | SDK does not publish UUIDs; cannot compare |
| Auth / notify / write characteristics | ❌ Unknown | SDK internals are `@RestrictTo(LIBRARY)`; not verifiable |
| CCCD descriptor `0x2902` | Expected, unconfirmed | Not verifiable from SDK |
| MTU | Unknown | SDK AAR `batch-size2-mtu`/`mtu2batch-size` docs hint at MTU/batch size tuning, value unknown |

**Key gap:** BlueBit has **not yet obtained a P6 GATT service dump** (`dumpServices()` output remains pending per `FitCloud_Protocol_Findings.md`). Without it, no characteristic-level comparison with the SDK is possible. The SDK's `FitCloudSdkBleConnection` in BlueBit is currently a no-op stub — all GATT is handled inside the SDK's `RxAndroidBle`-based connector, opaque to BlueBit.

---

## 7. Remaining Unknowns and Confidence Level

### Unknowns

1. **P6 GATT characteristic map:** Which characteristics under `0x3802` support read/write/notify/indicate; their UUIDs and properties. (No GATT dump captured yet.)
2. **SDK wire protocol for P6:** The exact command IDs, framing, checksums, encryption, and session establishment the SDK uses over the P6's GATT. Internal (`@RestrictTo(LIBRARY)`).
3. **Whether P6 requires pre-binding in the official FitCloud app** before SDK `connect(bindOrLogin=false)` can succeed.
4. **Which SDK version first added P6 support** (if any), given `3.0.2.4` vs `3.0.2.8` drift.
5. **Whether the P6's `0x3802` service is the same service the SDK talks to**, or whether the SDK uses a different service not advertised.

### Confidence level

**Low-to-moderate** that the SDK is directly compatible with the P6. The companyId `0x5A54` match and generic FitCloud targeting are positive signals, but there is **no direct evidence** (no P6 mention, no published UUIDs, no GATT dump) to confirm protocol compatibility. Do **not** claim the SDK supports the P6 without direct evidence.

---

## 8. Minimal, Safe Validation Plan

Execute only with the P6 (MAC 90:EB:99:D6:5F:99) and Samsung Galaxy A17 4G available, and only after user authorisation. All steps read-only or reversible; no firmware updates, factory resets, destructive writes, or unnecessary re-pairing.

1. **Prerequisites:** Confirm P6 is unpaired from the official FitCloud Pro app (or note current bond state). Confirm BlueBit debug build installs on the A17.
2. **Scan/discovery:** Run BlueBit; confirm `FitCloudProvider.canHandle()` matches the P6 advertisement (`0x3802` + companyId `0x5A54`). Log scan result.
3. **GATT dump (read-only):** Use the existing "Dump GATT Services" diagnostic to capture the P6's full service tree under `0x3802` (services, characteristics, properties, CCCDs). Save to `Reports/`; do not modify existing captures.
4. **SDK connect attempt:** Invoke `AndroidFitCloudProvider.connect()` with `bindOrLogin=false` (non-destructive login path, same `USER_ID = "bluebit_user"`). Observe `ConnectorState` transition to `CONNECTED` vs error. Log result.
5. **Read-only operations (if connected):** Call `configFeature().getDeviceInfo()` and `settingsFeature().requestLcdShape()` / `requestDialPushInfo()` via `FitCloudDialInspector`. Record project/patch/flash/app strings, IC type, LCD shape, feature flags.
6. **HCI capture (if needed):** Enable Android Bluetooth HCI snoop logging during steps 3–5; pull `btsnoop_hci.log` for offline analysis. Store alongside existing captures.
7. **Record:** SDK version `3.0.2.4`, Android version (A17), P6 firmware (from `getDeviceInfo`/`getExtraFirmwareInfo`), BLE services/characteristics, connection success/failure, and any error codes.
8. **Decision gate:** If `CONNECTED` + read-only queries return sensible P6 data → compatibility supported → proceed to Section 9 implementation plan. If connection fails or data is nonsensical → compatibility not established → treat SDK as "protocol reference only (C)" or "unsupported/inconclusive (D)".

---

## 9. Concrete Implementation Plan (affected files)

If validation succeeds, proceed with **SDK-assisted integration (B)** — keep device-specific logic behind a dedicated P6 adapter; do not mix FitCloud commands with Fitbit Inspire 3 code.

### Files to modify (Android only)

| File | Change |
|------|--------|
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitcloud/AndroidFitCloudProvider.kt` | Replace hardcoded `USER_ID`/dummy profile data with real values from connected device; expose `getDeviceInfo()`-derived identity; add `reconnect()` guard (already present); map `FcConnectorError` to BlueBit `Result` errors. |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitcloud/FitCloudSdkBleConnection.kt` | Replace no-op stub with a thin delegation layer if BlueBit needs direct GATT access; otherwise keep as SDK-managed marker. |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitcloud/FitCloudProvider.kt` | Implement `connect()` (currently `NotImplementedError`) to delegate to `AndroidFitCloudProvider`; populate `capabilities()` from `FcDeviceInfo.Feature` flags. |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitcloud/FitCloudUuids.kt` | Add any confirmed P6-specific characteristic UUIDs after GATT dump. |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/MainActivity.kt` | Already wires `AndroidFitCloudProvider`; no change needed beyond diagnostics exposure. |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitcloud/FitCloudDialInspector.kt` | Extend with characteristic-level read diagnostics once UUIDs are confirmed. |

### Files NOT to touch (safety restrictions)

- `bluebit/fitbit/*` — Inspire 3 implementation and authentication handling untouched.
- Existing HCI captures, protocol notes, historical research under `Reports/` — preserved.
- No production dependency changes before validation.

### New files (if integration proceeds)

- `Reports/FitCloud_P6_Validation_Report.md` — record of Section 8 results.
- `bluebit/fitcloud/FitCloudP6Adapter.kt` (optional) — dedicated P6 capability/feature adapter if SDK feature flags diverge from generic FitCloud behavior.

---

## 10. Decision

**Recommended outcome: B — SDK-assisted integration.**

The SDK is already integrated, builds, and its scanner accepts the P6's companyId `0x5A54`. However, direct P6 compatibility is **not yet established** because the SDK names no P6 profile, publishes no GATT UUIDs, and its protocol internals are internal/restricted. BlueBit's existing `0x3802` observations are consistent with the SDK's device discrimination but do not prove wire-protocol compatibility.

**Next step:** Execute the Section 8 validation plan (scan → GATT dump → SDK connect → read-only queries → record results) on the P6 with the A17. Only after documented success should any production integration code be finalized. If validation fails, the correct outcome shifts to C (protocol reference only) or D (unsupported/inconclusive), and BlueBit should continue its own P6 protocol investigation independent of the SDK.

---

*End of report.*