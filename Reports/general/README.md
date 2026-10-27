# BlueBit

A Kotlin Multiplatform research application for exploring, pairing, and communicating with Bluetooth Low Energy (BLE) wearables. BlueBit abstracts device families behind a provider model so that Fitbit, FitCloud, and future wearable ecosystems can be supported from a single codebase.

> **Status:** Active research / proof-of-concept. APIs, providers, and diagnostics are still evolving.

---

## What BlueBit Does

- **Scans** for BLE wearables using provider-specific filters.
- **Identifies** devices by advertisement data and known device profiles.
- **Pairs / authenticates** with devices using provider-specific protocols.
- **Exposes diagnostics** for reverse-engineering GATT services and connection behavior.
- **Syncs** activity, heart-rate, sleep, and other data where supported.

---

## Supported Platforms

| Platform | Support | Notes |
|----------|---------|-------|
| Android | ✅ Primary target | Full BLE, diagnostics, and UI |
| Desktop JVM | ✅ Secondary | Smoke tests and provider logic; BLE is NoOp/simulated |

---

## Architecture

BlueBit is organized around a **provider-based** architecture:

```
app/src/commonMain/kotlin/bluebit/
  core/        # Platform-agnostic BLE, connection, device, crypto, UI
  fitbit/      # Fitbit-specific provider, GATT constants, diagnostics
  fitcloud/    # FitCloud Pro-specific provider and constants
```

### Core abstractions

- `WearableProvider` — the integration point for a wearable family.
- `ProviderRegistry` — routes scan results to the correct provider.
- `ProviderContext` — scoped container passed to the UI and connection layers.
- `BleScanner` / `BleConnection` — platform-agnostic BLE primitives.
- `DeviceProfile` / `BlueBitDeviceMatcher` — generic device identification.
- `AdvertisementVerifier` / `KeyProvider` — AES-based advertisement verification.

### Rule

`core` never depends on `fitbit` or `fitcloud`. The UI depends only on `core`.

---

## Providers

### Fitbit

- Scan filter based on 6 known Fitbit service UUIDs.
- GATTlink transport for CoAP-over-GATT communication.
- Pairing API client (`validate.json`, `pair.json`, `ack.json`, `sync.json`).
- Diagnostics: GATT inspection, GATT cache, Gattlink, GoldenGate, bonding experiments.

### FitCloud Pro

- Scan filter based on the FitCloud primary service UUID.
- Basic device identification and connection scaffolding.

---

## Repository Layout

```
BlueBitApp/          # Kotlin Multiplatform source code
ApkInput/            # Original APKs used for research and decompilation
ApkOutput/           # Built BlueBit APKs
Tools/               # JADX and other reverse-engineering tools
Reports/             # Investigation reports (BLE, GATT, pairing, etc.)
com/                 # Extracted FitCloud SDK class references
*.md                 # Architecture proposal and deep-dive research notes
```

### Notable reports

- `architecture_proposal.md` — provider-based refactor plan.
- `Fitbit_Pairing_Deep_Research.md`
- `Fitbit_Whole_APK_Onboarding_Audit.md`
- `Inspire3_Gattlink_Startup_Investigation.md`
- `FitCloud_Refactor_Report.md`
- `Gadgetbridge_Investigation_Report.md`

### Excluded from version control

The following are kept out of the repo via `.gitignore`:

- `tmp_hw/` — temporary decompilation artifacts.
- `Decoded/` — regenerated decompiled APK output (~379 MB).
- `*.log`, `fitbit_logcat_*.txt` — runtime logs.
- `.gradle/`, `build/`, `.idea/`, `.kotlin/errors/` — build/cache directories.

---

## Building

BlueBit bundles its own Gradle distribution (`BlueBitApp/gradle-dist/`) and wrapper scripts, so you do not need a system-wide Gradle install.

### Android

```bash
cd BlueBitApp
./gradlew :app:assembleDebug
```

The resulting APK is written to `app/build/outputs/apk/debug/`.

### Desktop JVM

```bash
cd BlueBitApp
./gradlew :app:run
```

---

## Large Files & Git LFS

This repository uses **Git LFS** for binary assets that exceed GitHub's recommended size limits:

- `*.hprof` (heap dumps)
- `*.apk`
- `*.jar`
- `*.zip`
- `*.so`

Make sure Git LFS is installed before cloning:

```bash
git lfs install
git clone https://github.com/MrTech59/BlueBit.git
```

---

## Research Origin

BlueBit grew out of independent reverse-engineering research into Fitbit and FitCloud BLE protocols. The codebase mixes original Kotlin code, extracted SDK references, and extensive markdown investigation notes. It is intended for educational and interoperability research.

---

## Caution

- This project interacts with proprietary protocols and may not comply with vendor terms of service.
- Some bundled materials (APKs, decompiled sources, SDK extracts) are proprietary and included here solely for research purposes.
- Use at your own risk; the authors are not responsible for bricked devices or account restrictions.
