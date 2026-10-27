# Phase 18 — DTLS `-10601` Recovery Validation on the Samsung A17

**Status:** OFFLINE VALIDATION COMPLETE — **LIVE DEVICE VALIDATION NOT PERFORMED**
**Date:** 2026-10-25
**Target device:** Samsung Galaxy A17 4G + onboarded Fitbit Inspire 3 (`D8:9E:C1:BA:3D:E4`)
**Verdict:** `INCONCLUSIVE` for the Phase 18 hardware success criterion. No device was attached,
so no probe ran, no `-10601` recovery was observed in Phase 18, and nothing was installed.

> **This report does not declare the DTLS recovery issue resolved.** The offline work below shows
> that the current Kotlin state machine classifies the *historically recorded* native status
> sequence correctly. It does **not** show that the real Android/native stack still emits that
> sequence, nor that `DtlsBootstrapProbe` — which has never been recorded running on a device —
> can complete its BLE/Gattlink path. See §6 and §8.

---

## 1. Starting Git State

Recorded before any operation, and re-checked after all work (identical):

| Item | Value |
|------|-------|
| Branch | `main` |
| HEAD | `7d2342fd4a730f3bdeab30c93bc919876150f15f` |
| HEAD subject | `Add project README` (2026-10-05 16:32:29 +0100, Connor Odendaal) |
| Recent history | `7d2342fd4` Add project README · `4a0232529` Add Git LFS tracking for large binaries · `afd7756d6` Initial commit: transfer all BlueBit project files |
| `git status --porcelain` entries | **80** (23 tracked modified/deleted + 57 untracked) |
| Stashes | **0** |

Final re-check after all Phase 18 work: HEAD, branch, 80 entries, 23 tracked modifications,
57 untracked, 0 stashes — **unchanged**. No reset, clean, checkout, stash, commit, or overwrite
was performed. Existing captures and reports were opened read-only.

The 80 status entries are the pre-existing state carried from earlier phases (baseline before
Phase 17 was 78; `+2` are `.postman/` and `postman/`, created 2026-10-20, i.e. before that
session). Phase 18 changed none of them.

---

## 2. Source and APK Verification

### 2.1 Phase 17 changes are still present

`BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/DtlsProbeStateMachine.kt`
(mtime `2026-10-21 19:23:30`, 8 019 bytes):

| Marker | Line |
|--------|------|
| `const val DTLS_ERROR_UNKNOWN_IDENTITY = -10601` | 60 |
| `transientErrorCount` in `ProbeResult` (defaulted `0`) | 34 |
| `private var _transientErrorCount` / public getter | 88–89 |
| `if (code == DTLS_ERROR_UNKNOWN_IDENTITY && _stage != ProbeStage.FAILED)` → `PENDING` | 154–158 |
| `_transientErrorCount++` | 157 |
| `if (_stage == ProbeStage.FAILED)` → first terminal error kept authoritative | 159–161 |
| `state=2` transition-checked via `transitionTo(SESSION_ESTABLISHED)` (Phase 17 task-2 fix) | 139–151 |
| `transientErrorCount` propagated into `fail()` / `success()` / `cleanupResult()` | 185 / 201 / 217 |

Regression test present:
`BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/diagnostics/DtlsProbeStateMachineTransientErrorTest.kt`
(8 184 bytes, **12** `@Test` methods).

### 2.2 APK corresponds to current source

`.\gradlew.bat :app:assembleDebug --offline` → `BUILD SUCCESSFUL in 3s`,
**`39 actionable tasks: 39 up-to-date`**, exit 0. Nothing was recompiled, which confirms the
existing APK was already built from exactly this source — no rebuild was required.

| Artefact | Value |
|----------|-------|
| Path | `BlueBitApp/app/build/outputs/apk/debug/app-debug.apk` |
| Size | 27 462 589 bytes |
| mtime | 2026-10-21 19:24:30 |
| SHA-256 | `e8b66e2cccfb53d796c2cf2037554b358b65f31e50b0c26c65490ff0276da848` |
| `output-metadata.json` SHA-256 | `8e4ffca0fc8d05059871c9d08919d1dc6174a6a6bbc143526e87dc00d5670e2f` |
| Package / version | `bluebit.app`, versionCode `1`, versionName `1.0` |
| SDK | minSdk 26, targetSdk 35, compileSdk 35 (`platformBuildVersionName='15'`) |
| Launchable activity | `bluebit.app.MainActivity` |
| Application label | `BlueBit` |

Newest source file in `app/src` is `2026-10-21 19:23:39`, i.e. **51 s older than the APK**, and
the build is up-to-date — so the APK and the verified source are consistent. Badging was read
locally with `build-tools/36.0.0/aapt2.exe`; no device was involved.

> The APK was produced during the Phase 17 session. Phase 18 **verified** it is current; it did not
> rebuild it and did not install it.

### 2.3 How the live numbers would be observable (static check)

`MainActivity.kt:302-315` exposes the probe as diagnostic id `dtls_bootstrap_probe`
(button "Probe DTLS Bootstrap") and logs the whole data class:

```kotlin
Log.i("BlueBit", "DTLS_BOOTSTRAP_PROBE result=$result")
```

Because `ProbeResult` is a `data class`, its generated `toString()` includes
`transientErrorCount=`. The probe's own progress lines use tag **`BlueBitDTLSProbe`**;
the native stack's transitions use tag **`BlueBitGG_Stack`**; identity resolution uses
**`BlueBitGG_Tls`**. This is verified by reading the source, **not** by observing a run.

---

## 3. Automated Test Results

All runs used the project's offline Gradle workflow (`--offline`), JDK 17, Gradle 8.10.2.

| # | Suite | Command | Tests | F | E | S | Exit |
|---|-------|---------|-------|---|---|---|------|
| A | Phase 17 JVM harness (init script) | `:app:desktopTest --tests "bluebit.fitbit.phase17.*"` | **21** | 0 | 0 | 0 | 0 |
| B | Phase 17 Python harness | `python prototype_analysis/phase17/harness/run_all.py` | **54** | 0 | 0 | 0 | 0 |
| C | Full app desktop suite | `:app:desktopTest` | **94** | 0 | 0 | 0 | 0 |
| D | Phase 18 replay harness (init script) | `:app:desktopTest --tests "bluebit.fitbit.phase18.*"` | **7** | 0 | 0 | 0 | 0 |
| E | Debug APK build | `:app:assembleDebug` | — | — | — | — | 0 |

Suite A per class: `Phase17DtlsResetTest` 5, `Phase17MappingTest` 6, `Phase17ResolverWalkTest` 4,
`Phase17TransportInvariantTest` 6. Suite B printed `Ran 54 tests in 0.010s` then `OK`.
Suite C per class: `BleServiceDataTest` 5, `BlueBitDeviceMatcherTest` 7,
`DeviceServiceDataParserTest` 3, `FitbitGattUuidsTest` 8, `GattlinkConnectorTest` 3,
`GattlinkServiceSelectorTest` 4, `GattlinkStartupSequenceTest` 2, `AdvertisementVerifierTest` 3,
`CoapReadOnlyProbeTest` 15, `DtlsBootstrapProbeTest` 14,
`DtlsProbeStateMachineTransientErrorTest` 12, `GattlinkTxSerializerTest` 10,
`PairedDeviceCoordinatorTest` 8. Suite D: `Phase18RecordedSequenceReplayTest` 7.

### 3.1 Real production classes vs synthetic mirrors

| Test | Drives | Honest classification |
|------|--------|----------------------|
| `DtlsProbeStateMachineTransientErrorTest` (12) | real `commonMain.DtlsProbeStateMachine` | **real production class**, hand-built input |
| `DtlsBootstrapProbeTest` (14) | real `commonMain.DtlsProbeStateMachine` | **real production class**; its own KDoc (lines 11–14) states the Android probe is *not* exercised |
| `Phase18RecordedSequenceReplayTest` (7) | real `commonMain.DtlsProbeStateMachine` | **real production class + real recorded data** |
| `Phase17DtlsResetTest`, `Phase17TransportInvariantTest` | real `commonMain` classes | real production classes (per Phase 17 report) |
| `Phase17ResolverWalkTest`, `Phase17MappingTest` | synthetic mirrors | **mirror only** — not proof of production behaviour |
| Python harness (54) | Python model | **synthetic model**, explicitly labelled `[SYNTHETIC]` |

### 3.2 What none of these prove

No test in any suite exercises `androidMain` `DtlsBootstrapProbe`, the `Stack` JNI bridge,
`Bridge`/`TxSink`, `libxp.so`, Android BLE, or the Inspire 3. `libxp.so` ships only for
`arm64-v8a` and the GoldenGate bindings are `androidMain`-only, so the real stack cannot run on
this workstation. **A green JVM suite is not hardware validation.**

---

## 4. Device Readiness

| Check | Result |
|-------|--------|
| ADB binary | `C:\Users\Connor\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| ADB version | `1.0.41` / `37.0.0-14910828` |
| `adb devices -l` (before work) | `List of devices attached` — **no entries** |
| `adb devices -l` (re-checked after test runs) | `List of devices attached` — **no entries** |

**No device operations were performed.** Nothing was installed, pushed, pulled, uninstalled,
rebooted, scanned, paired, bonded, reset, or captured. `adb connect` was deliberately **not**
attempted: there is no recorded wireless-debugging endpoint in the project, and guessing one
would be a device operation with no diagnostic value.

### Exact prerequisite to proceed

1. Connect the Samsung Galaxy A17 4G by USB with **Developer options → USB debugging** enabled,
   and authorise the workstation's RSA key on the handset when prompted.
2. `adb devices -l` must list it with state **`device`** (not `unauthorized`, `offline`, or absent).
3. The Inspire 3 must remain **onboarded and bonded to that handset** — do not unpair, do not
   clear Bluetooth bonds, do not factory-reset, do not update firmware.
4. The BlueBit debug build must already be installed on the handset from the previously
   established diagnostic setup. Phase 18 did **not** verify the installed build, because that
   requires the device.

Until step 2 holds, Phase 18's hardware criterion cannot be attempted.

---

## 5. DTLS Trace

### 5.1 Phase 18 live trace

**There is none.** No device was attached, so no probe was run and no new logcat, HCI capture, or
trace exists for Phase 18. Nothing in this section should be read as a Phase 18 device observation.

### 5.2 Historical recorded sequences (used as offline replay input)

Source files (opened read-only; SHA-256 recorded and re-verified by the replay test):

| Source | SHA-256 |
|--------|---------|
| `prototype_analysis/captured-logs/phase16/phase16_live_logcat.txt` | `71d5cc0361ecbac970e7dcb81aa9e5ca576ef34a9a191ffe697a817e6db5f0f2` |
| `prototype_analysis/captured-logs/goldengate_diag_controlled_logcat.txt` | `2ec5264c53916470d72c55aee6d32b4b31847bbca963c6b50222a8ab69e2be6a` |

Extraction (see `evidence/recorded_dtls_sequences.tsv`): all
`BlueBitGG_Stack: onDtlsStatusChange state=… code=…` lines in file order, split into sessions at
gaps > 10 s (largest intra-session gap observed 2.13 s; smallest inter-session gap ≈ 54 min).

| Session | Source | Window | Sequence | `-10601` count |
|---------|--------|--------|----------|----------------|
| `S01` | phase16_live_logcat.txt | 10-19 16:33:04.647 → 16:33:05.244 | `1:0, 3:-10601, 0:0, 1:0, 0:0, 1:0, 2:0` | 1 |
| `S02` | goldengate_diag_controlled_logcat.txt | 10-18 16:24:58.515 → 16:25:00.804 | `1:0, 0:0, 1:0, 2:0` (control, no error) | 0 |
| `S03` | goldengate_diag_controlled_logcat.txt | 10-18 17:19:30.309 → 17:19:32.992 | `1:0, 0:0, 1:0, 3:-10601, 0:0, 1:0, 0:0, 1:0, 2:0` | 1 |

**Timestamp correlation for `S01`** (millisecond resolution, native + resolver tags interleaved):

```
16:33:04.634  BlueBitGG_Stack: Stack created
16:33:04.641  BlueBitGG_Stack: Stack.start() calling native...
16:33:04.647  BlueBitGG_Stack: onDtlsStatusChange state=1 code=0        <- handshake starts
16:33:04.941  BlueBitGG_Tls:   resolve called keyId='MD-A376B03C-6AF89570' nodeKey=D8:9E:C1:BA:3D:E4
16:33:04.941  BlueBitGG_Tls:   resolve result: NOT FOUND
16:33:04.941  BlueBitGG_Tls:   resolve called keyId='MD-A376B03C-6AF89570'   (second call, same identity)
16:33:04.941  BlueBitGG_Tls:   resolve result: NOT FOUND
16:33:04.946  BlueBitGG_Stack: onDtlsStatusChange state=3 code=-10601    <- 5 ms after the failed resolve
16:33:04.946  BlueBitGG_Stack: onDtlsStatusChange state=0 code=0        <- native reset to INIT
16:33:04.946  BlueBitGG_Stack: onDtlsStatusChange state=1 code=0        <- re-handshake
16:33:05.023  BlueBitGG_Stack: onDtlsStatusChange state=0 code=0
16:33:05.123  BlueBitGG_Stack: onDtlsStatusChange state=1 code=0
16:33:05.234  BlueBitGG_Tls:   resolve called keyId='BOOTSTRAP' nodeKey=D8:9E:C1:BA:3D:E4
16:33:05.234  BlueBitGG_Tls:   resolve result: NOT FOUND
16:33:05.234  BlueBitGG_Tls:   resolve called keyId='BOOTSTRAP'            (second call)
16:33:05.234  BlueBitGG_Tls:   resolve result: FOUND (16 bytes)
16:33:05.244  BlueBitGG_Stack: onDtlsStatusChange state=2 code=0        <- session established
```

Stack create → established: ~0.61 s, i.e. far inside the probe's 20 s
`DEFAULT_HANDSHAKE_TIMEOUT_MS`. Note the resolver is invoked **twice** per identity, and the
`BOOTSTRAP` pair shows `NOT FOUND` immediately followed by `FOUND (16 bytes)` at the same
millisecond — recorded fact, cause not established here.

### 5.3 Provenance correction (important)

1. **These sequences did not come from `DtlsBootstrapProbe`.** The dominant tag around the `S01`
   window is `BlueBitGG_Diag` (118 lines), which is
   `FitbitGoldenGateDiagnostic`'s `TAG` (verified at line 59 of that file). The specific
   diagnostic id that invoked it (`goldengate_test`, `goldengate_transport_only_test`, …) is not
   written to the log, so it is **not** asserted here. `FitbitGoldenGateDiagnostic.kt` contains **no**
   `DtlsProbeStateMachine` reference and **no** `setDtlsStatusListener` call — it only creates the
   `Stack(..., isNode = false)` and notes that DTLS status is observable through `BlueBitGG_Stack`.
   So during those captures the Kotlin state machine was **not in the path**; the logged events are
   the *native* notifications that `Stack.kt` forwards to a listener that did not exist for them.
2. **`DtlsBootstrapProbe` has no recorded live run.** The probe logs under tag `BlueBitDTLSProbe`,
   which appears in **zero** files under `prototype_analysis/captured-logs/` (the string
   `DTLS_BOOTSTRAP_PROBE` there is only the diagnostic-id list). Phase 16's APK also predates the
   `transientErrorCount` field, so **no existing capture contains `transientErrorCount=`**.

Consequently the replay below asks exactly one question: *if the native stack emits these
sequences again, does the current Kotlin machine classify them correctly?* It does not
retroactively prove the machine was ever exercised on hardware.

### 5.4 Offline replay result (real production class × real recorded data)

`Phase18RecordedSequenceReplayTest` mirrors the caller's pre-DTLS stage progression
(`NOT_STARTED → BLE_CONNECTION → BLE_DISCOVERY → GATTLINK_SERVICE_CHECK → GATTLINK_SETUP →
DTLS_HANDSHAKE`, as in `runProbe` lines 65/209/226/243) and then feeds the recorded pairs:

```
S01  1:0,3:-10601,0:0,1:0,0:0,1:0,2:0                   -> PENDING,PENDING,PENDING,PENDING,PENDING,PENDING,SESSION_ESTABLISHED
S02  1:0,0:0,1:0,2:0                                   -> PENDING,PENDING,PENDING,SESSION_ESTABLISHED
S03  1:0,0:0,1:0,3:-10601,0:0,1:0,0:0,1:0,2:0           -> PENDING,PENDING,PENDING,PENDING,PENDING,PENDING,PENDING,PENDING,SESSION_ESTABLISHED
S01 truncated at -10601  (1:0,3:-10601)                  -> PENDING,PENDING   (no establishment)
```

Asserted: `-10601` → `PENDING` (not `ERROR`, which would have completed the caller's deferred with
`false`); final stage `SESSION_ESTABLISHED`; `machine.error == null`; exactly one
`SESSION_ESTABLISHED` interpretation and it is last; zero `ERROR` interpretations;
`transientErrorCount == 1` for `S01`/`S03`, `0` for `S02`. Plus the two negative guards: a
truncated sequence yields `success=false` after the caller's timeout (never fabricated success),
and a late `state=2` arriving after `fail()` is rejected (`ERROR`) and cannot resurrect the probe.

---

## 6. Validation Outcome

| Criterion | Result | Evidence |
|-----------|--------|----------|
| Phase 17 code/tests still present and coherent | **PASS** | §2.1 |
| APK corresponds to verified source | **PASS** | §2.2 (39/39 up-to-date) |
| Offline suites green with exact counts | **PASS** | §3 (A–E) |
| Current machine classifies the historically observed native sequence correctly | **PASS (offline replay)** | §5.4 |
| `-10601` observed on real hardware during Phase 18 | **NOT OBSERVED** | no device |
| Real probe reaches established session after the transient error | **NOT TESTED** | no device |
| Native reset/retry corroborated by a Phase 18 log | **NOT TESTED** | no device |

**Overall: `INCONCLUSIVE` for the hardware criterion.** This is not a failure of the code and not
a success of the recovery handling — it is an untested boundary. Per the phase plan, missing
device evidence must be reported as inconclusive rather than inferred.

Specifically **not claimed**: that DTLS recovery works with the real Android/native stack; that
`-10601` is universally transient; that the Inspire 3's onboarding/bonding path was preserved by
any Phase 18 action (no action was taken, so nothing was put at risk); that
`transientErrorCount` was ever observed as `1` on a device.

---

## 7. Files Created or Changed

**Created (all new, all inside a new Phase 18 directory):**

| Path | Bytes | Purpose |
|------|-------|---------|
| `prototype_analysis/phase18/evidence/recorded_dtls_sequences.tsv` | 1 413 | Extracted real sequences + source SHA-256 + extraction rule |
| `prototype_analysis/phase18/harness/jvm/phase18-desktopTest.init.gradle` | 2 308 | Adds harness dir as extra `desktopTest` srcDir; passes `phase18.evidence.dir` |
| `prototype_analysis/phase18/harness/jvm/Phase18RecordedSequenceReplayTest.kt` | 12 334 | 7 tests; real `DtlsProbeStateMachine` × recorded sequences |
| `prototype_analysis/phase18/PHASE18_DEVICE_VALIDATION.md` | this file | Phase 18 report |

**Modified: none.** No existing source, test, build file, evidence capture, log, or report was
edited. `GattlinkTxSerializer`, `TxSink` status propagation, `Bridge` wiring and the Phase 14 FIFO
fix were not touched. Git status remains 80 entries / 0 stashes.

Side effects outside the repo: Gradle build outputs under `BlueBitApp/app/build/` (test XML /
classes, already gitignored) and one temp file under
`C:\Users\Connor\AppData\Local\Temp\opencode\phase18_pyharness.txt`.

### Exact commands used

```powershell
# state
git branch --show-current; git rev-parse HEAD; git log -10 --oneline; git status --porcelain; git stash list

# Phase 17 JVM harness
cd G:\Dev\BlueBit\BlueBitApp
.\gradlew.bat :app:desktopTest --tests "bluebit.fitbit.phase17.*" --offline `
  --init-script "..\prototype_analysis\phase17\harness\jvm\phase17-desktopTest.init.gradle"

# Phase 17 Python harness
cd G:\Dev\BlueBit
python prototype_analysis\phase17\harness\run_all.py

# full app desktop suite
cd G:\Dev\BlueBit\BlueBitApp
.\gradlew.bat :app:desktopTest --offline

# APK currency + build
.\gradlew.bat :app:assembleDebug --offline

# APK identity (local, no device)
& "C:\Program Files (x86)\Android\android-sdk\build-tools\36.0.0\aapt2.exe" dump badging `
  app\build\outputs\apk\debug\app-debug.apk

# device readiness (read-only)
& "C:\Users\Connor\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices -l

# Phase 18 replay harness
.\gradlew.bat :app:desktopTest --tests "bluebit.fitbit.phase18.*" --offline `
  --init-script "..\prototype_analysis\phase18\harness\jvm\phase18-desktopTest.init.gradle"
```

---

## 8. Remaining Uncertainties

1. **No live evidence at all for the recovery path.** The single most important unknown —
   whether the current APK recovers on the A17 — is untouched.
2. **`DtlsBootstrapProbe` has never been recorded running on hardware.** Its BLE connect → MTU →
   service discovery → `AC2F0145` ephemeral-pointer read → Gattlink notification enable → Stack
   start path is unit-tested only at the state-machine level. The historical sequences came from a
   *different* diagnostic, so the probe's own plumbing is an independent unknown.
3. **Native error mapping not re-verified in this workspace.** `-10601 =
   GG_ERROR_TLS_UNKNOWN_IDENTITY` (`GG_ERROR_BASE_TLS - 1`, base `-10600`) rests on static analysis
   of the decoded Fitbit sources, not on a runtime symbol lookup in this workspace's prebuilt
   `libxp.so`. The Kotlin constant could be right for the wrong reason.
4. **Native auto-reset is observed behaviour, not a contract.** Recovery in `S01`/`S03` depended on
   the peer re-offering a resolvable identity (`BOOTSTRAP`). Nothing guarantees that on a future
   run; if it does not happen, the probe correctly times out after 20 s.
5. **State `0`/`1` interleave is unstable across captures.** `S01` shows `3,0,1,0,1,2`; `S03`
   shows `3,0,1,0,1,2` after a longer pre-error tail. Both replay fine (any non-2, non-error state
   is `PENDING`), but a live run may differ again.
6. **`transientErrorCount` rendering is inferred from `data class` semantics**, not observed. It is
   threaded through `machine` → `ProbeResult` → `Log.i("BlueBit", "… result=$result")`, but no
   capture has ever contained the field.
7. **Multiplicity unknown.** The state machine increments per `-10601` notification, so `N > 1` is
   possible if the native stack repeats the failure. Only 1 per session was ever recorded; the
   count to expect live is therefore "≥ 1", not strictly "= 1".
8. **No Phase 18 HCI capture.** Without `btsnoop`, even a successful live DTLS handshake could not
   be confirmed at the ATT layer.

---

## 9. Recommended Next Phase

**Phase 19 — single controlled live transport-only probe run.**

Preconditions: §4 steps 1–4 satisfied (`adb devices -l` shows the handset as `device`; Inspire 3
remains onboarded/bonded).

Steps (one run only, nothing speculative):

1. Record `adb shell dumpsys package bluebit.app | findstr versionCode` and compare the installed
   build against APK `e8b66e2c…`. If they differ, **report first** and ask before replacing, since
   replacing alters the existing diagnostic setup.
2. `adb logcat -c`, then start a tagged capture to
   `prototype_analysis/phase18/captured-logs/phase19_<timestamp>_transport_only.logcat.txt`
   (new file; never overwrite existing logs).
3. Trigger **only** "Probe DTLS Bootstrap" (`dtls_bootstrap_probe`). Do **not** trigger
   `coap_readonly_probe`, `goldengate_*`, or pairing.
4. Let it finish once. Do not retry loop.
5. Accept if: `BlueBitGG_Tls` shows `MD-A376B03C… NOT FOUND` → `BOOTSTRAP FOUND`; `BlueBitGG_Stack`
   shows `state=3 code=-10601` → `state=0` → `state=1` → `state=2 code=0`; `BlueBitDTLSProbe`
   shows `PROBE_START` … `Cleanup: stage=SESSION_ESTABLISHED success=true` … `PROBE_END`; and
   `DTLS_BOOTSTRAP_PROBE result=` contains `success=true` with `transientErrorCount=1`.
6. If it stops at `state=3`, do not change the state machine. First identify the failing boundary
   (installed build? callbacks reaching Kotlin? native reset emitted? resolver result? caller
   timeout?) per the phase plan's §6 order.
7. Record outcome in a new `phase19` note; leave this Phase 18 file unedited.

Out of scope until the above passes: `bond/control`, `4.01`/`2.04` differences, MobileData
credentials, firmware, factory reset.
