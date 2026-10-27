# Phase 19 — Live DTLS Bootstrap Validation

**Date:** 2026-10-25 (23:02–23:14 Europe/Berlin, device clock GMT+2)
**Device:** Samsung Galaxy A17 4G (SM-A175F, serial `R5GL62WS7QY`), Android 16 (SDK 36), build `BP4A.251205.006.A175FXXS7CZI4`
**Tracker:** Fitbit Inspire 3, `D8:9E:C1:BA:3D:E4` (onboarded/bonded state untouched)
**Build under test:** `BlueBitApp\app\build\outputs\apk\debug\app-debug.apk`, sha256 `e8b66e2cccfb53d796c2cf2037554b358b65f31e50b0c26c65490ff0276da848` (= Phase 18 build, 2026-10-21 19:24:30)

## Verdict

**CONFIRMED — the existing `-10601` recovery handling works on the live Android/native stack, in one controlled run.**

Observed live, exactly once, end-to-end:
`state=1 → state=3 code=-10601 → state=0 → state=1 → [state=0 → state=1] → state=2 code=0 (SESSION_ESTABLISHED, psk='BOOTSTRAP')`,
with identity resolution `MD-A376B03C-6AF89570 NOT FOUND (×2) → BOOTSTRAP NOT FOUND then FOUND (16 bytes)` and the final probe line
`ProbeResult(stage=SESSION_ESTABLISHED, success=true, error=null, dtlsState=2, dtlsCode=0, txCount=8, rxCount=9, transientErrorCount=1)`.
Session established **~3.74 s** after `PROBE_START` (the 20 s `DEFAULT_HANDSHAKE_TIMEOUT_MS` never approached); `-10601 → state=2` recovery took **645 ms**.

This is a **single-sample** result. It proves the recovery path exists and executes on hardware for this tracker in this state; it does not prove `-10601` is always transient, and the native error-**name** mapping (`GG_ERROR_TLS_UNKNOWN_IDENTITY`) is still only statically derived (see §8).

## 1. ADB device verification

| Check | Result |
|---|---|
| `adb devices -l` (start) | `R5GL62WS7QY device product:a17xx model:SM_A175F device:a17 transport_id:1` |
| Identity | `ro.product.model=SM-A175F`, `ro.product.brand=samsung`, `ro.product.device=a17` |
| Android | release 16, SDK 36, build `BP4A.251205.006.A175FXXS7CZI4` (not debuggable device) |
| ADB version | platform-tools 37.0.0 (`C:\Users\Connor\AppData\Local\Android\Sdk\platform-tools\adb.exe`) |

**Incident:** at 23:02 the device dropped to `R5GL62WS7QY offline` immediately before the first install attempt (`adb install -r` → exit 1, *"device offline"*, **no change on device**). The user re-plugged/unlocked at ~23:06; `get-state` returned `device` (transport_id:5) and work resumed. No `kill-server`, no debug-setting changes.

BlueBit runtime permissions (checked twice, pre/post install): `BLUETOOTH_CONNECT` granted, `BLUETOOTH_SCAN` granted, `ACCESS_FINE_LOCATION` granted, `ACCESS_COARSE_LOCATION` granted. Neither BlueBit nor the Fitbit app was running at session start (`ps -A`).

## 2. Git state

| | Before (start of Phase 19) | After (end, 23:14) |
|---|---|---|
| branch | `main` | `main` |
| HEAD | `7d2342fd4a730f3bdeab30c93bc919876150f15f` | `7d2342fd4a730f3bdeab30c93bc919876150f15f` |
| `status --porcelain` entries | 80 | **80** |
| stashes | 0 | 0 |

Identical state. All Phase 19 artefacts were added exclusively inside the already-untracked `prototype_analysis/` directory, which `git status --porcelain` collapses to the single entry `?? prototype_analysis/` (that is why the count does not change; `git check-ignore` confirms the directory is **not** gitignored — only `/Decoded/` is). **No Phase 18 or earlier artefact was read-modified; no tracked source file was touched.**

## 3. Source / APK verification and the replace decision

### What was installed vs. what was to be tested

The installed BlueBit app was `versionCode=1 / 1.0`, `lastUpdateTime=2026-10-19 14:53:15`, `firstInstallTime=2026-09-02` (pre-existing, updated in place since 2026-09-02). Same versionCode/Name as the Phase 18 build, so package metadata could not distinguish builds. The installed APK was therefore **pulled from the device** (read-only copy) and compared against the Phase 18-verified APK:

| APK | sha256 | size | dex string hits `transient error state=` | `transientErrorCount` | `DTLS_ERROR_UNKNOWN_IDENTITY` |
|---|---|---|---|---|---|
| Installed (pulled, pre-install) | `e5ee2b539d6540a194e68509168331b583e04e32fad79a68edc39ab6a30d52db` | 27,462,216 | 1 | **0** | **0** |
| Phase 18 build (disk) | `e8b66e2cccfb53d796c2cf2037554b358b65f31e50b0c26c65490ff0276da848` | 27,462,589 | 1 | 3 | 1 |

DEX inventory (20 `classes*.dex` entries each) is byte-identical in size for 18 entries; only `classes4.dex` (+812 B) and `classes12.dex` (+28 B) differ.

**Interpretation:** the installed 10-19 build already contained the *pre-Phase-17* transient classification (`DTLS transient error state=$state code=$code` literal — this literal exists only in the `code == -10601 → PENDING` branch) but lacked the Phase 17 refinements (`transientErrorCount` counter, named `DTLS_ERROR_UNKNOWN_IDENTITY` constant, guard refinements). Both pre-existing string markers `DTLS handshake timed out` and `DTLS_BOOTSTRAP_PROBE result=` were present in both builds.

**Signature compatibility** (apksigner `verify --print-certs` on both APKs, local): identical debug certificate SHA-256 `cbfec86d18426835f4f9966eae0a23055b2c10cb43db8dca5e3355751d3374e2` — so installing was a same-signature, same-versionCode update-in-place (app data preserved; no uninstall, no downgrade).

**User approval was obtained before installation** (per task §2), after exactly the above risk analysis: "Install the Phase 18 APK".

### Install + verification

```
adb install -r BlueBitApp\app\build\outputs\apk\debug\app-debug.apk   → Success, exit 0 (23:07:25)
```

Post-install: `lastUpdateTime=2026-10-25 23:07:25`, `firstInstallTime` unchanged (same slot, data preserved), `versionCode=1`, `primaryCpuAbi=arm64-v8a`. The freshly installed `base.apk` was pulled back and **hashed to `e8b66e2c…` (exact match)**, and its dex contains `transientErrorCount` ×3 as expected — the tested build demonstrably carries the Phase 17 machine. App launched via `adb shell am start -n bluebit.app/bluebit.app.MainActivity` (exit 0); process `bluebit.app` PID 20106 up; BlueBit's stored device records were byte-identical before and after the install (§4).

## 4. State preservation (everything NOT touched)

- **Android/OS:** no unpair, no bond clearing, no Bluetooth/system settings changes, no reboots.
- `dumpsys bluetooth_manager` during the session: **0** `BOND_STATE` events. (MACs are privacy-masked in dumpsys on this build, so bond identities cannot be correlated; the Inspire 3 was already bond-known to the phone from onboarding.)
- **BlueBit app data:** `shared_prefs/bluebit_paired_devices.xml` unchanged end-to-end — still exactly one record: `[{"providerId":"fitcloud","address":"90:EB:99:D6:5F:99","name":"P6_","extra":{"project":"0000000052B6"}}]`. The Inspire 3 record seen in the Phase 16 era is not (and was not) in the persisted list; the user "re-added" it for this run and the probe flow never wrote a fitbit record back (documented: the persisted list remains the P6 only). `files/` still holds the pre-existing 5 gattlink dumps + `profileInstalled`; nothing deleted or replaced.
- **Fitbit side:** `com.fitbit.FitbitMobile` 5.10.1 untouched, never launched, its app data never read/written; Inspire 3 onboarding state not accessed.
- **FitCloud P6:** BlueBit performed **zero** operations against `90:EB:99:D6:5F:99`. After the probe had already finished (23:09:10), the **FitCloud Pro app itself** (PID 21728, `Fc#Connector`) re-established its own LE session to the P6 at 23:09:36–43 — unrelated to and un-triggered by BlueBit (flagged openly in §8). No contention occurred at any time.

## 5. Probe implementation / safety inspection (task §3, code-read, pre-run)

- **Trigger:** `MainActivity.kt:302-315` — `id="dtls_bootstrap_probe"`, `label="Probe DTLS Bootstrap"`, action runs `DtlsBootstrapProbe.runProbe(adapter.getRemoteDevice(address))` in `lifecycleScope`. The 13-item diagnostics list containing this button is created **only inside `FitbitProvider`** (`MainActivity.kt:192-201`). `AndroidFitCloudProvider.diagnostics()` returns `emptyList()` (line 209); its capability diagnostics are `dump_gatt` (a log stub), `query_device_info` and `query_dial` only.
- **Consequence (user decision point):** the probe cannot be triggered for the FitCloud P6 through the existing UI, and its GATT expectations are Fitbit-specific: Gattlink service `ABBAFF00/ABBAFF01/ABBAFF02`, Service-Changed (0x2A05), GattDatabase confirm pair (`AC2F0045`/`AC2F0145` ephemeral). The P6 is a FitCloud tracker advertising service `0x3802` (`FitCloudUuids.kt`, documented from the user's BLE capture) with a TopStep-WearKit-SDK connect path — no Gattlink/GoldenGate transport. Per the user's instruction, **the probe was therefore not run against the P6** and was instead validated on the Inspire 3 (approved).

### BLE/GATT operations performed by `DtlsBootstrapProbe` (all observed live, §6)

connectGatt (LE, autoConnect=false) → `requestMtu(185)` → `discoverServices` → enable Service-Changed **indications** (CCC 0x02) → read ephemeral pointer `AC2F0145` (16 bytes → `ac2f2645-…`) → write `0x00` to that ephemeral characteristic (GattDatabase confirmation write) → enable notifications (CCC 0x01) on Gattlink TX characteristic `ABBAFF02` → `Stack(config='DSNG', isNode=false)` + `CoapEndpoint.attach` (**no CoAP requests sent**) → `updateMtu(negotiatedMTU-3)`. Tracker-facing writes are limited to Gattlink tunnel data (the DTLS handshake bytes) and the standard GattDatabase-confirm `0x00`. No bond operations, no pairing, no tracker-setting writes, no firmware interaction.

## 6. Exact commands and results (chronological)

| # | Time | Command (device-side) | Result |
|---|---|---|---|
| 1 | 23:02 | `adb devices -l` | device listed offline (post-re-plug earlier); earlier checks all `device` |
| 2 | 23:02 | `adb install -r …` | **exit 1, "device offline" — nothing installed** |
| 3 | 23:06 | `adb get-state` | `device` (re-linked) |
| 4 | 23:07:06 | `Get-FileHash` re-check of APK | `e8b66e2c…` match → proceed |
| 5 | 23:07:25 | `adb install -r` | `Success`, exit 0 |
| 6 | 23:07:31 | `dumpsys package bluebit.app` + `pm path` | update-in-place verified |
| 7 | 23:07:33 | `run-as bluebit.app cat shared_prefs/*.xml` | records unchanged |
| 8 | 23:07:36 | `adb pull …/base.apk` → hash | `e8b66e2c…` exact |
| 9 | 23:07:38 | `am start -n bluebit.app/bluebit.app.MainActivity` | exit 0, app up (PID 20106) |
| 10 | 23:07:46 | `adb logcat -c`; background `adb logcat -v threadtime` → capture file | streaming 23:07:46.435 → 23:13:09.371 |
| 11 | 23:09:07–23:09:11 | **user taps "Probe DTLS Bootstrap" once** (on-device, by the user) | full run; see §7 |
| 12 | 23:12–23:14 | capture stopped (client processes terminated); post-run read-only checks | see below |

Operational notes: the first capture attempt used `adb logcat -f G:\…` which failed (`Read-only file system` — `-f` is a device-side path); retried correctly with stdout redirection. Nothing else failed or was aborted; the killed `cmd/logcat` clients affected only the local stream, never the device.

## 7. Observed trace and outcome

**Final result line (`MainActivity`):**

```
DTLS_BOOTSTRAP_PROBE result=ProbeResult(stage=SESSION_ESTABLISHED, success=true, error=null,
    dtlsState=2, dtlsCode=0, txCount=8, rxCount=9, transientErrorCount=1)
```

**DTLS status sequence** (native `BlueBitGG_Stack: onDtlsStatusChange` / probe `BlueBitDTLSProbe: onDtlsStatusChange`; both hooked, consistent, both logged below once):

| t (23:09) | event | note |
|---|---|---|
| 07.190 | `PROBE_START: name=Inspire 3 address=D8:9E:C1:BA:3D:E4` | correct transport-only probe, correct target |
| 07.496 | GATT connected (newState=2) | |
| 08.788 | `onMtuChanged mtu=247` | |
| 08.795 | `onServicesDiscovered status=0 services=7` | |
| 08.828 | Service-Changed CCC write status=0 | |
| 09.124 | ephemeral pointer read: 16 B → `ac2f2645-…` | |
| 09.317 | GattDatabase validation write complete | |
| 09.514 | Gattlink CCC on `abbaff02` enabled | |
| 09.516 | `Stack created, pointer=0x-4bfff…` (config='DSNG') | GG stack live |
| 09.526 | **state=1 code=0** | handshake started |
| 09.527 | `updateMtu(244) returned 0` | |
| 10.072 | RX 130 B (DTLS ClientHello) | tracker initiates PSK handshake |
| 10.089 | TX 160 B (ServerHello + auth) | |
| 10.252 | RX 151 B — contains length-20 ASCII `MD-A376B03C-6AF89570` | tracker requests MobileData identity on the wire |
| 10.258 | `resolve called keyId='MD-A376B03C-6AF89570'` ×2 → **NOT FOUND ×2** | resolver invoked twice per identity — matches recorded data |
| 10.262 | **state=3 code=-10601** | the transient unknown-identity alert |
| 10.263 | **state=0 code=0 → state=1 code=0** | native auto-reset → restart |
| 10.492 | state=0 code=0 (system event) | reset pulse during re-handshake (mirrors S01's second state=0) |
| 10.694 | state=1 code=0 | handshake #2 |
| 10.707 | RX 130 B (second ClientHello) | |
| 10.728 | TX 160 B | |
| 10.886 | RX 140 B — contains length-9 ASCII `BOOTSTRAP` | tracker now requests BOOTSTRAP identity |
| 10.897 | `resolve 'BOOTSTRAP'` NOT FOUND → **FOUND (16 bytes)** | chain: first resolver misses, next resolver has the key |
| 10.902 | TX 104 B | |
| 10.907 | **state=2 code=0** — `psk='BOOTSTRAP' pskHex=424F4F545354524150` | **SESSION ESTABLISHED** |
| 10.909 | `Cleanup: stage=SESSION_ESTABLISHED success=true tx=8 rx=9` | orderly teardown |
| 10.913 | `Stack destroyed` | |
| 10.916 | `Bridge closed` | |
| 10.929 | **`PROBE_END: SESSION_ESTABLISHED`** | |

Timings: `PROBE_START → state=2` = **3.717 s** (timeout budget 20 s, unused). `stack start → -10601` ≈ 736 ms; `-10601 → state=2` = **645 ms**.

**Cross-check against the machine-recorded sequences (Phase 18, from real captures):** shape matches S01 exactly — MD-identity requested and missed (×2 resolver calls), alert 115 path → `-10601`, immediate native reset (`state=0/1`), doubled reset pulse, second ClientHello, `BOOTSTRAP` resolve (NOT FOUND then FOUND, 16 bytes), `state=2` ~10 ms after FOUND (S01: 10 ms), successful short session well inside timeout. The offline replay prediction for both recovery sequences (`success=true, transientErrorCount=1`) is **now observed on hardware**.

**Absence checks:** exactly **1** `PROBE_START` in the whole capture (the one run, never retried); **0** CoAP-readonly activity; **0** bond-change events; **0** P6-address activity by BlueBit during the probe window; **0** crashes of `bluebit.app`.

## 8. Uncertainties and limits

1. **Single sample.** One successful run validates the path, not its probability. No claim that `-10601` is always transient, and no multi-run/aging behaviour was tested (by design — one probe only).
2. **Native error-name mapping still statically derived.** `-10601 == GG_ERROR_TLS_UNKNOWN_IDENTITY` rests on the Decoded Fitbit sources, not on this workspace's prebuilt `libxp.so`; the *behaviour* (transient, non-terminal, followed by native reset) is now hardware-proven, the *symbolic name* is not.
3. **CoAP plaintext inside DTLS remains unavailable and was not fabricated.** No CoAP requests were sent (`CoapEndpoint.attach` only); nothing application-layer was observed.
4. **The intermediate 10-19 build** (pre-Phase-17 refinements) was never exercised live; its exact runtime behaviour on `-10601` stays untested.
5. **Offline failure paths** (20 s timeout with `success=false` + preserved counter, late-`state=2` rejection) were already covered by the Phase 18 JVM replay tests on real production classes, but were **not** triggered on hardware (not attempted — would have required a second run).
6. **FitCloud Pro reconnect at 23:09:36** (26 s after probe end) is observed but unattributed — the app's own auto-reconnect cycles are documented in its AppRecords earlier in the day; BlueBit played no part in it.
7. **Ads/firmware:** the `PROBE_END` line and stream also confirm no firmware/OTA interactions; the only 2 RX hex dumps contain pure DTLS handshake records (content types 22/23 labels visible as `FEFD…` fragments). Interpretation of bytes other than the ASCII identity fields is explicitly out of scope here.
8. **Device clock** is GMT+2; all times local.

## 9. Files in this phase (all new; nothing replaced)

| Path | sha256 | note |
|---|---|---|
| `captures/phase19_probe_run_2026-10-25_2320.logcat.txt` | `cd484903f67ece88314731fd464bd0b800867202865a5d36f50c56b4dfd46ffd` | full logcat 23:07:46.435–23:13:09.371, 14,069,723 B |
| `captures/phase19_probe_relevant_lines.txt` | `6d76afef13def220b05a8072797e203bad471c7060bed897756614fef965e880` | 104 tagged lines (DTLSProbe/Stack/Tls/Bridge), extraction-verified against final capture |
| `installed-apk/bluebit.app.installed_2026-10-25.base.apk` | `e5ee2b539d6540a194e68509168331b583e04e32fad79a68edc39ab6a30d52db` | immutable pre-install pull (27,462,216 B) |
| `installed-apk/bluebit.app.after_phase19_install.base.apk` | `e8b66e2cccfb53d796c2cf2037554b358b65f31e50b0c26c65490ff0276da848` | post-install pull — equals Phase 18 build (27,462,589 B) |
| `capture_scan.ps1` | — | analysis helper |
| `PHASE19_DEVICE_VALIDATION.md` | this file | |

Production source: **unchanged** (0 edits; git state byte-identical, §2). Phase 18 artefacts: unmodified.

## 10. Recommended next steps

1. **Close the `-10601` investigation loop** in `BlueBitApp/dtls_error_minus_10601_investigation.md` with a pointer to this report: behaviour now hardware-validated (transient by observation), but keep the "not universally transient" wording — the tracker-side resolvable identity was re-offered only because this is the standard Hello→BOOTSTRAP sequence on every BlueBit session.
2. **P6 support (separate work item, per user intent).** The blocker is structural, not a bug: no Gattlink/GG transport and no fitbit-provider diagnostics exist for the FitCloud path (§5). A Phase 20 plan should (a) decide whether P6 stays on the TopStep-SDK path (then: SDK-integration diagnostics, not `DtlsBootstrapProbe`), or (b) defines Gattlink/GG bring-up for the P6 — requires tracker-side GATT evidence (service `0x3802` capture analysis) before any code work.
3. **Remaining hardware unknowns for the Fitbit path** (each a single controlled run, pending approval): timeout path on hardware; behaviour on a second consecutive probe (aging/freshness); whether the stored fitbit record re-addition ought to persist for stability (currently ephemeral).
4. **Do not create a release** off this phase; consider Phase 17+18+19 together sufficient only for a "validated" label on the recovery logic, with the A/C-vs-D MobileData-provisioning question still open (unchanged from Phase 18).
