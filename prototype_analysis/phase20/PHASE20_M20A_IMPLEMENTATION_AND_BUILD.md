# Phase 20 Follow-up — M20-A Implementation & Build/Install Validation

**Date:** 2026-10-25 23:17–23:30 (workstation clock)
**Scope:** approved work only — `-10601` doc addendum, M20-A persistence implementation, offline tests, build + in-place install validation.
**Hardware stop respected:** no launch of BlueBit, no scan/connect/pair/bond/probe, no BLE operation of any kind, no tracker interaction, no diagnostics triggered. Install-adjacent checks were read-only (`pm path`, `dumpsys`, `run-as` cat, one `adb pull` for hash).

---

## 1. Changes made (exact paths)

| # | Path | Type | Change |
|---|---|---|---|
| 1 | `BlueBitApp/dtls_error_minus_10601_investigation.md` | doc (already-untracked file) | appended the approved Phase 19 evidence addendum (§2) |
| 2 | `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/FitbitProvider.kt` | production (file already ` M` in baseline) | M20-A: +24 lines delta (imports ×4; `_pairedEvents`/`pairedDeviceEvents` + KDoc; `tryEmit` record on connect success) |
| 3 | `BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/FitbitProviderPairedEventTest.kt` | new test file | 7 offline tests (§4) |

Diff artefacts (new, phase20-owned):
- `prototype_analysis/phase20/diffs/FitbitProvider.kt.pre_m20a.diff` — full `git diff HEAD` of the file **before** M20-A (the pre-existing 14-insertion user change; sha256 `d1f8e82913441521…`),
- `prototype_analysis/phase20/diffs/FitbitProvider.kt.post_m20a.diff` — full `git diff HEAD` **after** M20-A (`38 insertions(+), 1 deletion(-)` cumulative; the M20-A delta = +24 lines; sha256 `cb6fdc43ed693a8f…`).

Nothing else was written into the tree; `prototype_analysis/phase20/installed-apk/bluebit.app.after_m20a_install.base.apk` is a pulled copy (evidence, §5).

## 2. Documentation addendum applied

`BlueBitApp/dtls_error_minus_10601_investigation.md` now ends with **"## Addendum — hardware validation (Phase 19, 2026-10-25)"**, containing exactly the patch approved in Phase 20 report §4: link to `prototype_analysis/phase19/PHASE19_DEVICE_VALIDATION.md`, capture path + sha256 (`cd484903f67ece88314731fd464bd0b800867202865a5d36f50c56b4dfd46ffd`), the live sequence (`MD-A376B03C-6AF89570` ×2 NOT FOUND → `state=3 code=-10601` → `state=0/1` native reset → `BOOTSTRAP` NOT FOUND→FOUND 16 B → `state=2 psk='BOOTSTRAP'`, 645 ms recovery, ~3.7 s probe), the single-sample (n=1) limitation, the static-derivation caveat for the symbolic name, and the explicit "no CoAP application data obtained / transience not established" statements. Historical sections untouched; no other doc edited.

## 3. Persistence implementation and design rationale

**Minimal behavioral surface, mirroring the existing FitCloud pattern end-to-end:**

```kotlin
// FitbitProvider.kt (delta)
private val _pairedEvents = MutableSharedFlow<PairedDeviceRecord>(extraBufferCapacity = 16)
override val pairedDeviceEvents: Flow<PairedDeviceRecord> = _pairedEvents.asSharedFlow()

// inside connect(), only in the success branch, after _connectionState.value = Connected:
_pairedEvents.tryEmit(
    PairedDeviceRecord(providerId = id, address = device.address, name = device.name ?: "Fitbit Device")
)
```

Rationale:
- **Same architecture**: the coordinator (`PairedDeviceCoordinator.kt:92-99`) persists exactly provider-emitted `PairedDeviceRecord`s — no new persistence code, no store schema change (`PairedDeviceRecord(providerId,enabled extra default; name, address)` unchanged, provider `id="fitbit"`).
- **Emission point**: exactly one site, the success branch of `connect()` — covers both the generic GATT connect path and (if later approved on hardware) the Inspire-3 pairing-handler path, because both terminate at the same success block.
- **Failure-safe**: `connect()` failure path (catch block) emits nothing (tested).
- **Non-blocking**: `tryEmit` + 16-slot buffer — connect() can never hang on a slow collector; the coordinator subscribes at `start()` (wired immediately in `MainActivity.kt:376-380`), so drops are not expected; and even a dropped event is self-healing because the next successful connect re-upserts (dedupe key `(providerId, address)` in `SharedPreferencesPairedDeviceStore.kt:39-44` — duplicates impossible).
- **Restore loop-safe**: `WearableProvider.reconnect` default falls back to `connect()` (`WearableProvider.kt:28`), so `restoreOnLaunch()` (`MainActivity.kt:489`) routes fitbit records through `FitbitProvider.connect` → connected-state + re-emission → upsert of the identical record. Proven by test.
- **Preserved by construction**: no change to DTLS/Gattlink/GoldenGate/identity resolution/`GattlinkTxSerializer`/`TxSink`/`Stack`/`Bridge`, none to FitCloud P6 behaviour, no pairing/bond/tracker logic added.

## 4. Tests and exact results

New file `FitbitProviderPairedEventTest.kt` (kotlin.test + `runTest`/`backgroundScope`/`runCurrent`, reusing the existing shared `bluebit.bluetooth.FakeBleConnection` for the success path; a private `ThrowingBleConnection` for the failure path; `RecordingStore` mirroring `InMemoryStore` of the coordinator test):

1. `successful connect emits a fitbit paired-device record` — emit + Connected state.
2. `failed connect emits no paired-device record` — no emission + Disconnected state.
3. `coordinator persists the fitbit record through the store` — end-to-end provider→coordinator→store.
4. `repeated connects upsert the same record instead of duplicating it` — 3 successive connects → exactly one record.
5. `store records for different providers or addresses are preserved when upserting` — P6 record coexists untouched.
6. `restored fitbit record routes through the provider's own connect` — `restoreOnLaunch()` on a pre-seeded fitbit record drives the real provider to Connected and re-upserts without duplicates.
7. `provider reports the fitbit id and a name fallback when the scan result is anonymous` — `name=null` → "Fitbit Device".

Run commands and results (all `--offline`, JDK 17 via `org.gradle.java.home`):

| Suite | Command | Result |
|---|---|---|
| Targeted | `.\gradlew.bat :app:desktopTest --offline --tests 'bluebit.fitbit.FitbitProviderPairedEventTest'` in `BlueBitApp` | **7/7 pass**, exit 0 |
| Full desktop suite | `.\gradlew.bat :app:desktopTest --offline` | **101 tests / 14 classes, 0 failures, 0 errors, 0 skipped**, exit 0 (94 pre-existing incl. the 12 Phase-17 regression tests + 7 new) |
| Phase 17 JVM harness | `.\gradlew.bat :app:desktopTest --tests 'bluebit.fitbit.phase17.*' --offline --init-script '..\prototype_analysis\phase17\harness\jvm\phase17-desktopTest.init.gradle'` | **21 tests** (DtlsReset 5, Mapping 6, ResolverWalk 4, TransportInvariant 6), 0 failures, exit 0 |
| Phase 18 replay | `.\gradlew.bat :app:desktopTest --tests 'bluebit.fitbit.phase18.*' --offline --init-script '..\prototype_analysis\phase18\harness\jvm\phase18-desktopTest.init.gradle'` | **7 tests, 0 failures**, exit 0 |
| Phase 17 Python harness | `python prototype_analysis\phase17\harness\run_all.py` | `Ran 54 tests ... OK`, exit 0 |

Not run (unchanged from prior phases, not part of this scope): instrumented `androidTest` (no device-ops approval), Compose UI-navigation tests do not exist in this repository.

## 5. Build and installation validation

**Build** (documented procedure, `BlueBitApp`):
- Command: `.\gradlew.bat :app:assembleDebug --offline` → `BUILD SUCCESSFUL`, 39 tasks (up-to-date where already current), **exit 0**.
- Re-run full capture for the record also `BUILD SUCCESSFUL in 1s`, exit 0.
- APK path: `G:\Dev\BlueBit\BlueBitApp\app\build\outputs\apk\debug\app-debug.apk`
- **SHA-256: `3a94212771636dc38e303607cbe924e83a3cd46ed8a975551f93c09a228a5e6d`**, 27,462,589 bytes, mtime 2026-10-25 23:28:26.
- Recurring benign SDK warnings (`Exception while marshalling … package.xml. Probably the SDK is read-only`) also present in earlier phases; unrelated to the build result.
- `apksigner verify --print-certs`: signer cert SHA-256 `cbfec86d18426835f4f9966eae0a23055b2c10cb43db8dca5e3355751d3374e2` — **identical to the installed build's certificate** (Phase 19), so same signature, no data reset path.
- `aapt dump badging`: `name='bluebit.app' versionCode='1' versionName='1.0'` — same versionCode as installed → **not a downgrade**; `adb install -r` in-place update is the correct (and previously proven) form.

**Install** (approved):
- Pre-state: `lastUpdateTime=2026-10-25 23:07:25` (Phase 19 install), `firstInstallTime=2026-09-02`.
- `adb install -r` → `Performing Streamed Install / Success`, exit 0.
- Post-state: `lastUpdateTime=2026-10-25 23:29:18`, `firstInstallTime` unchanged (same slot, **app data preserved** — no uninstall, no clear).
- `BLUETOOTH_CONNECT` runtime grant retained.
- BlueBit stored device list unchanged: still exactly one record (P6/fitcloud) — nothing written by this phase.
- Installed APK pulled back and hashed: **`3a942127…` exact match** with the built APK.
- Dex-marker provenance on the installed build: `transientErrorCount` ×3 (Phase 17 machine retained), `getPairedDeviceEvents` ×4 (interface + both provider overrides + coordinator-side refs — M20-A present), `PairedDeviceRecord` ×11, `FitbitProvider` ×20.

**Not done post-install (per instructions):** app launch, any BLE/scan/connect activity, any diagnostics, any Fitbit-app inspection, logcat capture.

## 6. Git status before/after

| | Before M20-A (baseline recorded in `baseline_git_status_porcelain.txt`) | After (end of this run) |
|---|---|---|
| branch / HEAD | `main` / `7d2342fd4a730f3bdeab30c93bc919876150f15f` | identical |
| entries / stashes | **80 / 0** | **80 / 0** |
| `FitbitProvider.kt` | ` M` (pre-existing: +14/−1) | ` M` (pre-existing +24 M20-A lines = +38/−1 cumulative) |

Why the count does not change: both new artefact locations are inside already-untracked containers — the test file joins the pre-existing untracked dir `?? BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/`, and the addendum lives inside the already-untracked `?? BlueBitApp/dtls_error_minus_10601_investigation.md`; all phase20 evidence is inside the single collapsed entry `?? prototype_analysis/`. **New changes of this run:** FitbitProvider.kt delta (above), new test file, addendum text, phase20 artefact files. Pre-existing modifications, deletions, and all earlier captures were preserved byte-for-byte (spot checks confirm phase18/19 report mtimes/sizes unchanged).

## 7. Limitations and checks that could not be performed offline

1. **No hardware evidence yet** — by design. Persistence-on-hardware, restore-on-launch reconnect, and any repeatability datapoint require the separate hardware approval (M20-A acceptance criteria in the Phase 20 report).
2. The inline KDoc references `restoreOnLaunch` on the coordinator link text; clarifying comment-only, no doc/code mismatch risk — reviewed, correct as-implemented (`PairedDeviceCoordinator.restoreOnLaunch` KDoc, lines 29-30).
3. The one operational hiccup this run was self-inflicted and harmless: the first `adb pull` targeted a not-yet-existing `phase20\installed-apk` directory (task created the directory and retried successfully). Device state unaffected at any point.
4. `MutableSharedFlow` emission with buffer 16 can in principle drop an event if the collector were absent and 16 un-consumed events queued; in the wired app the coordinator subscribes at creation time, and any lost event is re-emitted/de-duped on the next successful connect. Accepted and documented rather than over-engineered.
5. Instrumented/UI test coverage: not available offline (no Compose UI tests in repo, no device-ops approval for `androidTest`).

---

*New Phase 20 artefacts: `PHASE20_M20A_IMPLEMENTATION_AND_BUILD.md` (this file), `diffs/FitbitProvider.kt.pre_m20a.diff`, `diffs/FitbitProvider.kt.post_m20a.diff`, `installed-apk/bluebit.app.after_m20a_install.base.apk`, plus the pre-existing `baseline_git_status_porcelain.txt` (unchanged).*

**STOPPED before all hardware validation per instructions. Awaiting explicit separate approval for any Inspire 3 connection/persistence validation on the real tracker (e.g., one controlled UI connect → verify persisted fitbit record → relaunch → verify restoreOnLaunch reconnect, and optionally a single re-probe).**
