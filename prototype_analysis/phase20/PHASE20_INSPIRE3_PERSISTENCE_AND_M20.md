# Phase 20 — Inspire 3 Reliability & Persistence Investigation

**Date:** 2026-10-26 (investigation session; no device operations performed)
**Mode:** investigation & planning ONLY — no production edits, no installs, no hardware runs, no tracker/phone state changes.
**Primarly target:** Fitbit Inspire 3 (`D8:9E:C1:BA:3D:E4`). FitCloud P6 treated as existing secondary functionality, preserved as-is, not investigated further.

---

## 1. Workspace baseline and safety checks

| Item | Value |
|---|---|
| Repo root | `G:/Dev/BlueBit` |
| Branch / HEAD | `main` / `7d2342fd4a730f3bdeab30c93bc919876150f15f` (unchanged from Phase 18/19 close-out) |
| git | 2.55.0.windows.2 |
| porcelain entries | **80** — 12 tracked-modified (` M`), 11 tracked-deleted (` D`), 57 untracked (`??`), 0 renames |
| stashes | **0** |

Full porcelain output captured verbatim to:
`prototype_analysis/phase20/baseline_git_status_porcelain.txt` (7,460 bytes, exact output preserved).

Interpretation of the 80-entry state (identical to the Phase 19 baseline):
- The 12 ` M` files are the **pre-existing user changes** to production sources (`MainActivity.kt`, `AndroidBleScanner.kt`, `Inspire3PairingHandler.kt`, `FitbitGoldenGateDiagnostic.kt`, `Bridge.kt`, `TxSink.kt`, `Stack.kt`, `BlueBitAppShell.kt`, `BleLogWatchdog.kt`, `DeviceDetailScreen.kt`, `FitbitProvider.kt`, `AndroidManifest.xml`). **Untouched; not reverted; not regenerated.**
- The 11 ` D` files are pre-existing deletions (workspace documentation files moved out of the tree earlier). Untouched.
- All phase evidence lives inside the single collapsed untracked entry `?? prototype_analysis/` (not gitignored — `git check-ignore` proves it does not match any ignore rule, only `/Decoded/` is ignored).
- Phase 19 artefacts verified present and untouched:
  `phase19/PHASE19_DEVICE_VALIDATION.md`, `captures/phase19_probe_run_2026-10-25_2320.logcat.txt`,
  `captures/phase19_probe_relevant_lines.txt`, `installed-apk/bluebit.app.installed_2026-10-25.base.apk`,
  `installed-apk/bluebit.app.after_phase19_install.base.apk`.

**Deliberately not performed:** `git reset/clean/stash/checkout/restore/pull/push`, any `git add/commit`, any modification or deletion of existing captures or production files.

## 2. Phase 19 evidence summary and its limits

Confirmed on hardware (single controlled run, 2026-10-25 23:09:07–23:09:11):

```
PROBE_START name=Inspire 3 address=D8:9E:C1:BA:3D:E4
state=1 → state=3 code=-10601 → state=0 → state=1 → state=2 code=0 (psk='BOOTSTRAP')
resolve 'MD-A376B03C-6AF89570' ×2 → NOT FOUND ×2 → resolve 'BOOTSTRAP' NOT FOUND → FOUND (16 bytes)
ProbeResult(stage=SESSION_ESTABLISHED, success=true, error=null, dtlsState=2, dtlsCode=0,
            txCount=8, rxCount=9, transientErrorCount=1)
PROBE_END: SESSION_ESTABLISHED
```

- `-10601 → state=2` recovery window: **645 ms**; full probe: **~3.7 s** (20 s timeout budget unused).
- **Single sample only.** Demonstrates the recovery path exists and executes on hardware; it does **not** demonstrate repeatability, probabilistic behaviour, or universal transience of `-10601`.
- Preserved state: 0 bond events, 0 Fitbit-app interactions, stored prefs unchanged (P6-only), no CoAP traffic sent, CoAP plaintext inside DTLS never obtained.
- Symbolic error **name** (`GG_ERROR_TLS_UNKNOWN_IDENTITY`) derivation is still **static** — it comes from the separate reference repo `G:/Dev/fitbit-air-notification-bridge/` (`xp/tls/gg_tls.h:182-186` etc.), not from this workspace's prebuilt `libxp.so`. The hardware run confirmed the *behaviour* (`transient, non-terminal, followed by native reset`), not the *name*.
- Evidence paths (unchanged): `prototype_analysis/phase19/PHASE19_DEVICE_VALIDATION.md`, `phase19/captures/*`, phase18 replay TSV + `Phase18RecordedSequenceReplayTest` (7 tests, real production class), `BlueBitApp/dtls_error_minus_10601_investigation.md`.

## 3. Persistence-path findings (read-only source trace)

**Finding (source-established): the Inspire 3 entry was never persisted because the Fitbit provider has no paired-device emission and no UI "add" action writes to the store — persistence is structurally fitcloud-only by current design. No fitbit record can be written by any existing code path; the Phase 19 observation is explained without invoking any persistence bug.**

### 3.1 The chain of evidence (exact references)

1. **The only production writer** of the persisted device list is `PairedDeviceCoordinator.collectPairedEvents` — `app/src/commonMain/kotlin/bluebit/core/pairing/PairedDeviceCoordinator.kt:92-99` → `store.upsert(record)`. A project-wide grep confirms **no other production `upsert` call site** (only the coordinator itself; `forget()`/`remove()` at lines 84-90 have no production callers either — the only reference is the unit test).
2. The coordinator persists **exactly what providers emit on `pairedDeviceEvents`** and nothing else.
3. **FitbitProvider never overrides `pairedDeviceEvents`.** `commonMain/kotlin/bluebit/fitbit/FitbitProvider.kt` (full file read) overrides `canHandle/scanFilter/identify/connect/disconnect/capabilities/sync/diagnostics` but **not** `pairedDeviceEvents`; the base default is `emptyFlow()` at `commonMain/kotlin/bluebit/core/provider/WearableProvider.kt:31`. Therefore no fitbit device can ever be persisted, even after a fully successful `connect()` (`FitbitProvider.kt:68-85`).
4. **FitCloud does override it** — `androidMain/kotlin/bluebit/fitcloud/AndroidFitCloudProvider.kt:148-165`: `connector.observerConnectorState().filter { CONNECTED }.map { … PairedDeviceRecord(providerId="fitcloud", …) }`. Combined with the SDK auto-reconnect (`setAutoReconnectMode(BALANCED)`, line 66) and `PairedDeviceCoordinator.start()`/`restoreOnLaunch()` wiring (`MainActivity.kt:375-380`, `MainActivity.kt:487-493`), this is why `bluebit_paired_devices.xml` contains exactly one record — the P6 — which gets **re-upserted on every CONNECTED**: `[{"providerId":"fitcloud","address":"90:EB:99:D6:5F:99","name":"P6_",…}]` (observed live in Phase 19, unchanged after install and after the probe).
5. **The UI device list is ephemeral.** `commonMain/kotlin/bluebit/BlueBitAppShell.kt:51` `discoveredDevices = remember { mutableStateListOf<UiDevice>() }`; scan results (`:55-84`) are keyed only by address and placed in this in-memory list. `DevicesScreen` renders it (`:116-137`); `onDeviceClick` carries only `name/address` (`:135`).
6. **Device-detail navigation uses no persisted data.** The detail screen resolves the provider from the **scan-derived** `selectedDevice.providerId` (`:153-158`), and renders `deviceName/deviceAddress` from navigation params with hardcoded-empty fields (`:176-186`: `signalStrength=""`, `lastConnected=""`, `battery=0`, `model=""`, `firmwareVersion=""`). Connection toggling goes through provider `connect()` and only touches coordinator *retry flags* (`userDisconnect` `:202`, `userConnect` `:213`) — **never the store**.
7. **The probe uses a temporary device object, not a persisted record.** Diagnostics receive `deviceAddress` (`commonMain/kotlin/bluebit/core/ui/screens/DeviceDetailScreen.kt:122-127`) which is the ephemeral scan-result address; `MainActivity.kt:302-315` maps it through `adapter.getRemoteDevice(address)`. Nothing is read from or written to `SharedPreferencesPairedDeviceStore`. This fully explains the Phase 19 observation: the Inspire 3 appeared on a fitbit-provider screen (provider resolved from the live scan result — `FitbitProvider.canHandle` matches the advertised ABBA/FD6x UUID family, `FitbitProvider.kt:35-46`) while the persisted list stayed P6-only.
8. **The store itself is provider-neutral and multi-record.** `androidMain/kotlin/bluebit/pairing/SharedPreferencesPairedDeviceStore.kt:39-44` appends/replaces keyed by `(providerId,address)`; `:63-70` prefs name `bluebit_paired_devices`. Nothing in it could have prevented a fitbit upsert or removed a fitbit record (`remove()` is reachable only via `forget()`, which has no production caller). Also, the fitbit record cannot have been "added then lost" through this store: there is no truncating write path (`save()` always persists the full list, `:50-61`).
9. **Normal scan/add flow as implemented:** Devices screen → `Start Scan` → `AndroidBleScanner.startScan()` performs a **Fitbit-UUID filtered scan** (`androidMain/kotlin/bluebit/core/bluetooth/android/AndroidBleScanner.kt:67-91`, filters at `:120-148`: ADABFB00, FD63, ABBAFB00, ABBAFF00, FD62, 0x181D); results are matched by `ProviderRegistry.resolve` → fitbit provider; tapping a device opens the detail screen; `CONNECT` runs `FitbitProvider.connect` (with the Inspire-3 pairing-handler path via `inspire3PairingHandler`, `MainActivity.kt:355-368`). **End of flow** — there is no save/add-to-list step for fitbit. (The P6 appears in the UI only under `startUnfilteredScan()`, `:93-118`, since it advertises `0x3802`, `AndroidFitCloudProvider.kt:72-74`.)
10. Dead/parallel path clarified: `commonMain/kotlin/bluebit/core/connection/DeviceConnectionManager.kt` (`connect()`/`sync()` are `TODO("Not yet implemented")`, `:52-53`, `:67-68`) is **not** part of the active UI chain.

### 3.2 Existing test coverage

| Area | Test | File | Notes |
|---|---|---|---|
| Coordinator add/persist | `paired event is persisted` | `desktopTest/kotlin/bluebit/pairing/PairedDeviceCoordinatorTest.kt:125` | provider-agnostic; uses a fake provider that emits records |
| Coordinator restore/routing | `restoreOnLaunch routes each record through its owning provider`, `restoreOnLaunch skips device that is already connected`, plus unknown-provider, extra-key and forget cases (`:98-234`) | same file | the fixture even seeds a fake **fitbit** record (`:103`) — routing is generic |
| Device matching/identity | `BlueBitDeviceMatcherTest` | `desktopTest/kotlin/bluebit/bluetooth/BlueBitDeviceMatcherTest.kt` | scan-result → profile |
| GATT/Gattlink/DTLS probe logic | `DtlsBootstrapProbeTest`, `DtlsProbeStateMachineTransientErrorTest` (12), `GattlinkStartupSequenceTest`, `GattlinkServiceSelectorTest`, `GattlinkConnectorTest`, `GattlinkTxSerializerTest`, `FitbitGattUuidsTest`, `CoapReadOnlyProbeTest` | desktopTest | offline logic only |
| **UI navigation / screens** | **none found** (no test for `BlueBitAppShell`, `DevicesScreen`, `DeviceDetailScreen`) | — | gap |
| **`SharedPreferencesPairedDeviceStore`** | **none found** | — | gap |
| **`AndroidFitCloudProvider.pairedDeviceEvents`** | **none found** | — | gap (android-specific) |

### 3.3 Residual uncertainty (honest)

- We **cannot reconstruct historical prefs contents** (no backup exists); the claim "no fitbit record was *ever* written" rests on the current code (the single upsert call site + overridden/never-emitting fitbit flow + append-only store) and on the Phase 19 live observation. If genuinely desired, history could be checked only from prior captures (none contains such an event at present).
- This is a **structural design gap, not a regression**: there is no evidence any earlier build persisted fitbit devices either.

## 4. Proposed minimal documentation patch for `-10601` (FOR REVIEW — not applied)

Target file: `BlueBitApp/dtls_error_minus_10601_investigation.md` — append the following section immediately after the existing `## Conclusion` block (i.e., at end of file, after the current final line). Nothing else in the document changes.

```markdown
## Addendum — hardware validation (Phase 19, 2026-10-25)

The predicted recovery behaviour was observed once on real hardware: Samsung Galaxy A17 4G
(SM-A175F, Android 16) + onboarded Fitbit Inspire 3 (D8:9E:C1:BA:3D:E4), transport-only
`DtlsBootstrapProbe`, one controlled run (no retries). Full evidence:
`prototype_analysis/phase19/PHASE19_DEVICE_VALIDATION.md`; capture
`prototype_analysis/phase19/captures/phase19_probe_run_2026-10-25_2320.logcat.txt`
(sha256 cd484903f67ece88314731fd464bd0b800867202865a5d36f50c56b4dfd46ffd).

Observed live sequence (§7 of that report):

    state=1 code=0
    resolve 'MD-A376B03C-6AF89570' ×2 → NOT FOUND ×2   (ClientKeyExchange identity on the wire)
    state=3 code=-10601
    state=0 code=0 → state=1 code=0                    (native server-mode auto-reset, as §6 predicts)
    resolve 'BOOTSTRAP' NOT FOUND → FOUND (16 bytes)
    state=2 code=0 (psk='BOOTSTRAP')                   (SESSION_ESTABLISHED 645 ms after -10601)
    cleanup success=true; transientErrorCount=1 in ProbeResult

Precision of the claims:
- The **behaviour** is now hardware-confirmed with these limits: it is a **single sample**
  (n=1, one device, one firmware state). This demonstrates the recovery path in the released
  build works in practice; it does **not** show that `-10601` is always transient, nor quantify
  recovery time beyond this one observation (645 ms recovery window, ~3.7 s total probe).
- The **symbolic name** `GG_ERROR_TLS_UNKNOWN_IDENTITY` for `-10601` remains **statically
  derived** from the reference sources in `G:/Dev/fitbit-air-notification-bridge/`
  (xp/tls/gg_tls.h:182-186). It has not been verified against this workspace's prebuilt
  `libxp.so`; hardware observes numeric codes only.
- The identity bytes are visible in the handshake on the wire (the resolver log lines and the
  ClientKeyExchange payload agree), but **no CoAP application data was obtained** at any point
  (no CoAP requests were sent; `CoapEndpoint.attach` only).
- No bond changes, no Fitbit-app interaction, no tracker settings or firmware changes; BlueBit's
  persisted device list was not modified by the run.
```

The patch: links the evidence, separates hardware-observed behaviour from the statically-derived name, keeps the single-sample limitation, makes no "always transient" claim, and states plainly that no CoAP data was obtained. **Awaiting approval before it is applied.**

## 5. Recommended next Inspire 3 milestone (M20-A) and acceptance criteria

### Already demonstrated (hardware, Phase 19 — do not overstate)

- GATT/服务 discovery MUT negotiate MTU, Gattlink transport w/ native GG stack (`DSNG`, server mode), DTLS-PSK bootstrap with the full `-10601` transient-recovery path, orderly teardown. Diary evidence bluebit_ble dumps unchanged.

### Source-supported but hardware-unproven (avenues for validation)

- Persistence & lifecycle plumbing is *designed and unit-tested* for the provider-neutral coordinator (`PairedDeviceCoordinatorTest`), but the fitbit leg never participates (§3).
- `Inspire3PairingHandler` cache-validation pairing sequence exists and is wired (`MainActivity.kt:355-368`) — live outcome unproven in Phase 19 scope (the probe skipped the connect path entirely): it remains a candidate experiment under approval.
- `CoapReadOnlyProbe` and the full `FitbitGoldenGateDiagnostic` family exist and compile but are gated behind explicit approvals (CoAP for functional containment reasons from earlier phases).
- Repeatability/reproducibility of the bootstrap is unmeasured (n=1).

### Unresolved questions (unchanged from Phase 18, resting)

- A/C vs D MobileData-credential provisioning (would require forbidden factory-reset or real MobileData-authenticated session) — tracker denies bond/control and `/sync/config` on an owned device in the BOOTSTRAP-only configuration.
- Native error-name mapping (`libxp.so` symbol binding).

### Recommended milestone — **M20-A: make the Inspire 3 a first-class, persisted device (smallest, evidence-based, protocol-frozen)**

Rationale: after Phase 19 the only missing piece for routine re-use of the working transport/DTLS path is lifecycle/persistence; correctness of protocol is already hardware-proven (n=1). Persistence requires ~2 small production changes plus tests, all in already-modified-untracked 安全 zones (`FitbitProvider` is already ` M`), with zero protocol/DTLS/Gattlink behaviour change.

Proposed shape (for separate approval; not implemented in Phase 20):
1. Give `FitbitProvider` a mechanism to emit `pairedDeviceEvents` on successful connect (e.g. accept an emitter factory, mirror the shape used by `AndroidFitCloudProvider.kt:148-165`; emit `PairedDeviceRecord(providerId="fitbit", address, name)` only in `connect()`'s success branch, `FitbitProvider.kt:78-79`).
2. No UI change otherwise needed: tapping CONNECT behaves the same, and from then on the record survives; `restoreOnLaunch()` (`MainActivity.kt:489`) already routes fitbit records through the fitbit provider (tested generically in `PairedDeviceCoordinatorTest:98-106`).
3. New offline tests: fitbit provider emits exactly one record on connect-success (and none on failure), coordinator upsert dedupes, restore routes fitbit; no factory reset/pairing-path change; `GattlinkTxSerializer`/`TxSink`/Bridge wiring untouched.

Acceptance criteria (hardware validation step requires separate approval):
1. One controlled UI connect of the scanned Inspire 3 → `bluebit_paired_devices.json` contains a `providerId=fitbit` record for `D8:9E:C1:BA:3D:E4` (verifiable read-only via `run-as`).
2. App relaunch → `restoreOnLaunch()` logs a fitbit reconnect attempt through `FitbitProvider` (logcat `BlueBitBle`/`BlueBit` lines), record retained.
3. Full existing offline suites pass (94 desktop + 21 Phase-17 JVM + 54 Python) **plus** the new tests.
4. Optional (separately approved, single run): `Probe DTLS Bootstrap` once more after reconnect to confirm behaviour is unchanged vs Phase 19 (repeatability datapoint, still not a statistical claim).

### Explicitly out of scope for M20-A

FitCloud P6 transport or protocol work (existing P6 functionality preserved untouched); any change to `DtlsProbeStateMachine`/`-10601` semantics; CoAP request flows; changes to `GattlinkTxSerializer`/`TxSink`/`Stack`/`Bridge` behaviour; tracker/phone state changes beyond one approved connect.

## 6. Risks, unknowns and actions deliberately not performed

**Risks / unknowns**
- n=1 for every hardware claim in this area; nothing here pre-approves a second run.
- `inspire3PairingHandler` path behaviour on this tracker/bond state is unverified in this phase; connecting for real may exercise the PIN dialog or pairing sequence — treat as its own controlled experiment.
- Detail-screen metadata fields are placeholders by design (`BlueBitAppShell.kt:181-186`) — enriching them is future scope, not part of M20-A.
- The historical absence of fitbit records in prefs cannot be reconstructed absent backups (documented in §3.3).
- The A/C-vs-D provisioning question remains locked behind forbidden operations (unchanged).

**Deliberately not performed in Phase 20**
- No production code changes (including the proposed `pairedDeviceEvents` addition).
- The `-10601` documentation patch is **proposed only**, not applied.
- No APK build/install; no ADB connection to the phone; no logcat capture; no probe or CoAP-readonly run; no connect/disconnect on the tracker; no bond/pairing/unpair actions; no edits to Phase 18/19 evidence; no git state mutations (verified: 80 entries, HEAD unchanged, 0 stashes).

---

*Phase 20 artefacts (all new): `prototype_analysis/phase20/PHASE20_INSPIRE3_PERSISTENCE_AND_M20.md` (this file), `prototype_analysis/phase20/baseline_git_status_porcelain.txt`.*
