# BlueBit

A Kotlin Multiplatform research application for exploring, pairing, and communicating with Bluetooth Low Energy (BLE) wearables. BlueBit abstracts device families behind a provider model so that Fitbit, FitCloud, and future wearable ecosystems can be supported from a single codebase.

> **Status:** Active research / proof-of-concept. APIs, providers, and diagnostics are still evolving.
> **Current milestone (Phases 13–20):** Inspire 3 pairing sequence validated on real hardware; paired-device persistence with restore-on-launch (M20-A); notification-permission denial no longer blocks BLE features. See [Current Progress](#current-progress-phases-13--20).

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

## Current Progress (Phases 13–20)

Research proceeds in numbered phases under `prototype_analysis/`, each adding offline-testable progress plus a timestamped report. Highlights of the current state:

| Phase | Focus | Status |
|-------|-------|--------|
| 13–16 | Rescue/verification of the connection layer (issue `-10601`), DTLS/Gattlink transport work and diagnostics tooling | Offline-tested; superseded patches reverted |
| 17 | Transport invariants, protocol mapping, resolver-walk, DTLS-reset semantics | 21 JVM + 54 Python harness tests, all pass offline |
| 18 | Recorded-sequence replay harness validating protocol behavior against captured device traffic | 7 replay tests pass offline |
| 19 | First successful real-device pairing of the Fitbit **Inspire 3** (`PAIRING_END success=true`) over the full GATTlink/CoAP/DTLS stack | Validated on a Samsung Galaxy A17 4G |
| 20 | **Inspire 3 persistence (M20-A)**: UI connect → paired-device store persistence → relaunch auto-restore; plus a connection flow that no longer requires the notification permission | Implemented, tested offline; persistence leg hardware-validated |

Key behaviors added in phase 20:

- **Paired-device persistence** (`PairedDeviceCoordinator` + `SharedPreferencesPairedDeviceStore`): a successful UI connect records the provider/address/name; on the next launch `restoreOnLaunch()` attempts to reconnect automatically (distinguishable in logs from a confirmed connection). Deduplication safe across sessions; survived a full phone reboot.
- **Notification-free connection flow**: `POST_NOTIFICATIONS` (which only governs the visibility of the app's own notifications, e.g. the diagnostics foreground-service notification) never disables BLE features. BLE-critical permissions (`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`) are classified separately via `DevicePermissionPolicy`.
- **In-app connection status**: the device detail screen shows real connection state (`Connecting…` / `Connected` / failure note) with a retry action reusing the same button; no fake success states.
- **Diagnostics tooling**: BLE diagnostic logging foreground service (`connectedDevice` type), GATT/Gattlink/GoldenGate experiments, and a read-only CoAP probe.

The full offline test suite (`:app:desktopTest`) currently runs **114 tests across 16 classes, 0 failures**, plus the phase 17/18 harnesses and the Python protocol harness — all green. A rebuild-and-install hardware revalidation of the phase 20 follow-up APK is pending user approval.

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
- **Inspire 3 pairing handler**: full connect → service discovery → session-reset request/response handshake, hardware-validated (phase 19/20).
- **Paired-device persistence**: providers emit paired events, persisted per device, with automatic restore-on-launch and user-disconnect handling (M20-A, phase 20).
- Diagnostics: GATT inspection, GATT cache, Gattlink, GoldenGate, bonding experiments, and a read-only CoAP probe.

### FitCloud Pro

- Scan filter based on the FitCloud primary service UUID.
- Basic device identification and connection scaffolding.
- Shares the paired-device persistence coordinator with the Fitbit provider.

---

## Repository Layout

```
BlueBitApp/            # Kotlin Multiplatform source code
ApkInput/              # Original APKs used for research and decompilation
ApkOutput/             # Built BlueBit APKs
Tools/                 # JADX and other reverse-engineering tools
Reports/               # Investigation reports (BLE, GATT, pairing, etc.)
prototype_analysis/    # Numbered research phases: reports, harnesses, offline tests, evidence
com/                   # Extracted FitCloud SDK class references
*.md                   # Architecture proposal and deep-dive research notes
```

### Notable reports

- `architecture_proposal.md` — provider-based refactor plan.
- `Fitbit_Pairing_Deep_Research.md`
- `Fitbit_Whole_APK_Onboarding_Audit.md`
- `Inspire3_Gattlink_Startup_Investigation.md`
- `FitCloud_Refactor_Report.md`
- `Gadgetbridge_Investigation_Report.md`
- `prototype_analysis/phase20/` — M20-A persistence + notification-gate follow-up: acceptance criteria, implementation/build report, hardware-capture evidence, and SHA-256 inventories of installed APKs.

### Excluded from version control

The following are kept out of the repo via `.gitignore`:

- `tmp_hw/` — temporary decompilation artifacts.
- `Decoded/` — regenerated decompiled APK output (~379 MB).
- `*.log`, `fitbit_logcat_*.txt` — runtime logs.
- `.gradle/`, `build/`, `.idea/`, `.kotlin/errors/` — build/cache directories.
- Curated push (2026-10-27): raw `*.apk` / `*.zip` artifacts, the bulk logcat captures under `prototype_analysis/`, and decompiled-output trees (`apk_analysis_8B01Fj/`, `Reports/apk/`, `prototype_analysis/Fitbit-base-classes6-show-bad-code/`, `prototype_analysis/tmp-dex/`). These stay local-only; the accompanying reports record their SHA-256 identities so any offline copy can be verified, and they are all regenerable from the original APKs or from device captures.

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

### Offline tests

The unit/simulation suites run fully offline (no device required):

```bash
cd BlueBitApp
./gradlew :app:desktopTest --offline          # full suite: 114 tests / 16 classes
```

Phase-specific harnesses also run offline, e.g.:

```bash
./gradlew :app:desktopTest --offline \
  --tests 'bluebit.fitbit.phase17.*' \
  --init-script ../prototype_analysis/phase17/harness/jvm/phase17-desktopTest.init.gradle
```

`prototype_analysis/phase17/harness/run_all.py` runs the Python protocol-side harness (54 tests).

---

## Large Files & Git LFS

This repository uses **Git LFS** (when such files are committed) for binary assets that exceed GitHub's recommended size limits:

- `*.hprof` (heap dumps)
- `*.apk`
- `*.jar`
- `*.zip`
- `*.so`

Since 2026-10-27 the working policy is **curated**: newly produced research artifacts (device captures, bugreport zips, installed-APK copies, decompiled output) are kept **local only** and excluded via `.gitignore`; the phase reports under `prototype_analysis/` record their SHA-256, path, and provenance, so they remain verifiable without bloating the repository. Reports and small text/pcap/csv evidence are committed.

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
