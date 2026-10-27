# Phase 17 — Offline Authentication Investigation & Local Test-Harness Plan

**Status:** PROPOSAL (analysis complete; nothing implemented, nothing run on any device)
**Date:** 2026-10-19
**Mode:** Source inspection + offline evidence analysis only
**Nature:** This is a **plan**. It proposes work; it does not perform it. No implementation, no build, no install, no device interaction is authorised by this document.

---

## 0. Scope, constraints, and how this document was produced

### 0.1 Objective

Explain, from sources and already-captured evidence only, **why the Fitbit Inspire 3 firmware returns CoAP `4.01 Unauthorized` for `PUT bond/control` while `PUT pair/display` returns `2.04 Changed` in the same DTLS session**, and propose an isolated local DTLS/CoAP test harness that can exercise the relevant BlueBit code with synthetic credentials.

### 0.2 Constraints honoured while producing this document

- Source inspection and **offline analysis only**.
- **No** device/phone/tracker interaction, **no** ADB, **no** scan, **no** pair/unpair/bond/reset.
- **No** APK build, rebuild, or install.
- **No** firmware, account, Bluetooth-bond, or persistent-settings change.
- **No** existing source file, captured-evidence file, or the investigation report was modified here.
- Working tree preserved: `HEAD 7d2342fd4a730f3bdeab30c93bc919876150f15f`, stash `0`, **78** pre-existing dirty entries (re-verified at the time of writing). The only addition is **this new file** (`prototype_analysis/PHASE17_AUTH_HARNESS_PLAN.md`), which did not previously exist. Nothing was committed, stashed, reset, or cleaned.
- Synthetic identities only in the proposed harness. The document **does not** claim, derive, or fabricate the tracker's real MobileData key; only public/hard-coded material and clearly-labelled synthetic values are used.
- Secrets/personal data redacted; the 4-digit display code and any phone PIN are **not** reproduced.

### 0.3 Evidence verification

All conclusions below are tied to files already in the repository. No file was re-derived from an unverified input. Key inputs and the phase-16 hashes are listed in §12. Where the earlier narrative reports disagree with the **raw** captured evidence, the raw evidence wins and the discrepancy is flagged (see §3.4).

---

## 1. Executive summary

1. **The phone (BlueBit) is the DTLS *server*; the tracker is the DTLS *client*.** BlueBit builds the GoldenGate stack with `isNode = false` → GoldenGate "hub" → `GG_STACK_ELEMENT_TYPE_DTLS_SERVER`. The tracker sends the ClientHello and the ClientKeyExchange, i.e. it **presents the PSK identity**, and BlueBit resolves the key. (This corrects the earlier shorthand "BlueBit is the DTLS client".)

2. **The tracker's default PSK identity is a per-device MobileData identity, `MD-A376B03C-6AF89570`.** BlueBit's resolver chain is only `Hello ("hello") → Bootstrap ("BOOTSTRAP")`; it has **no** MobileData resolver and **no** MobileData key. So BlueBit answers `unknown_psk_identity` (fatal alert 115), the native stack reports `GG_ERROR_TLS_UNKNOWN_IDENTITY (-10601)` and resets, and the tracker **retries with `BOOTSTRAP`**, which BlueBit *can* resolve. Every BlueBit session is therefore a **BOOTSTRAP-authenticated** session. This is deterministic and reproducible (Phase 14 and Phase 16 identical).

3. **`PUT bond/control` and `PUT pair/display` are, on the Android side, identical apart from URI path and protobuf body.** Same endpoint mapper, same `CoapEndpoint`, same DTLS session, same key resolution, no per-resource credential and no per-resource handshake step. Therefore the `4.01` vs `2.04` split is imposed **entirely by the tracker firmware's per-resource authorization** under a BOOTSTRAP session.

4. **`4.01 → RF_ERR_KEY_EXPIRED (5378)` is a pure Android-side mapping, not evidence of an expired key.** `CoapResponseUtils.getResponseCode()` unconditionally returns `RF_ERR_KEY_EXPIRED` for CoAP `4.01` and — unlike other codes — **discards any device-supplied `ExtendedError`**. The enum name is a label, nothing more.

5. **The official-capture evidence shows the same "restricted under BOOTSTRAP" behaviour.** In a real, previously-provisioned session the tracker pushed MobileData metadata to `/md/3d02` and the peer answered `4.01 Unauthorized`; `/sync/config` also returned `4.01` early in the bootstrap session, while `/pair/display` returned `2.04`. So `4.01` on a resource under BOOTSTRAP is normal and resource-specific, not unique to `bond/control`.

6. **Leading explanation (composite, medium confidence):** a `bond/control` under a **BOOTSTRAP** session on a **tracker that is already provisioned with a MobileData identity** is refused. Two sub-hypotheses remain to be separated offline (§9): (a) resource-specific authorization requires a **MobileData-authenticated** session; (b) the tracker is already bonded/provisioned and requires a specific pairing/provisioning sequence (e.g. the server-mediated `/sync/dump → backend → /sync/response` path reported by the Gadgetbridge work).

7. **A fully-local harness cannot exercise BlueBit's *native* DTLS stack on this workstation.** The GoldenGate native library ships only as `arm64-v8a/libxp.so` (no x86/x86_64), and the bindings are `androidMain`-only (no desktop native). The harness therefore operates at the **protocol/oracle layer** (pure-JVM + Python) and constrains hypotheses rather than reproducing the tracker. The *true* end-to-end harness requires a future, separately-approved ARM Android target and a test build.

---

## 2. Task 1 — DTLS roles, identities, certificate/PSK, credential load and identity reset

### 2.1 Roles

| Side | BLE role | GoldenGate role | `isNode` | DTLS role | Evidence |
|------|----------|-----------------|----------|-----------|----------|
| Phone (BlueBit) | Central | **Hub** | `false` | **Server** | `FitbitGoldenGateDiagnostic.kt:549-556` (`isNode = false`); `DtlsBootstrapProbe.kt` / `CoapReadOnlyProbe.kt` likewise |
| Tracker (Inspire 3) | Peripheral | **Node** | `true` | **Client** | `inspire3_analysis_output/dtls_handshake_summary.csv` rows 1-8 and 12-16 (tracker sends ClientHello + ClientKeyExchange); `inspire3_protocol_analysis_report.md:225-229` |

JNI mapping (reference implementation, same native library as `libxp.so`):

- `reference_transport_api_report.md:172` — *"**Hub (peripheral / `isNode == false`):** `GG_STACK_ELEMENT_TYPE_DTLS_SERVER` with a `TlsKeyResolver` delegating to the Kotlin chain (`TlsKeyResolverRegistry.resolvers`)."*
- `reference_transport_api_report.md:173` — *"**Node (central / `isNode == true`):** `GG_STACK_ELEMENT_TYPE_DTLS_CLIENT` with the hard-coded bootstrap PSK identity/key."*
- `reference_transport_api_report.md:497` — `PeerRole.Peripheral, // phone is the BLE central but GG "hub" (isNode=false)`.

**Consequence:** the **client (tracker) chooses and sends the PSK identity**; the **server (phone) resolves the key**. Authentication is therefore server-resolves-client-identity, and mutual in the PSK sense (both ends must hold the same PSK or the `Finished` MAC fails).

### 2.2 Certificate vs PSK

- PSK-only. Cipher suite selected: **`TLS_PSK_WITH_AES_128_CCM` (`0xC0A4`)**, DTLS 1.2 (`0xfefd`). Evidence: `dtls_handshake_summary.csv` rows 2-3; `inspire3_protocol_analysis_report.md:23-24`.
- **No certificates, no X.509 trust anchors.** There is no chain to validate and no server-identity assertion beyond possession of the PSK.

### 2.3 The resolver chain and the credentials

BlueBit (`BlueBitApp/app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/`):

| Resolver | Identity (key id) | Key | Source |
|----------|-------------------|-----|--------|
| `HelloTlsKeyResolver` | `"hello"` | `00 01 02 … 0F` (public, trivial) | `HelloTlsKeyResolver.kt:16-21`; decoded `HelloTlsKeyResolverKt.java:12-15` |
| `BootstrapTlsKeyResolver` | `"BOOTSTRAP"` | `81 06 54 E3 36 AD CA B0 A0 3C 60 F7 4A A0 B6 FB` (public, hard-coded) | `BootstrapTlsKeyResolver.kt:17-22`; decoded `BootstrapTlsKeyResolverKt.java:12-15` |
| *(none)* | `MD-…` | — | **absent in BlueBit** (grep for `MobileData`/`MD-` under `BlueBitApp/app/src` → 0 matches) |

Chain construction: `TlsKeyResolverRegistry.kt:12-28` (Hello head → Bootstrap appended). Resolution walk: `TlsKeyResolver.kt:28-45`.

Official Fitbit app additionally has:

- `Decoded/…/goldengate/dtls/MobileDataTlsKeyResolver.java` — resolves `MD-…` identities from a `plj mobileDataKeyProvider` (per-device key store).
- `Decoded/…/goldengate/dtls/MobileDataTlsKeyId.java` — `{keyId, keyExpiration}`; i.e. MobileData keys are **per-device and time-bounded**.

**Credential load:** the `BOOTSTRAP` key is a compile-time constant shared by all devices (public); MobileData keys are provisioned per device and are **not** present in BlueBit or in any captured artifact.

### 2.4 Identity-reset sequence (verified in Phase 14 and Phase 16)

Phase 16 raw log (`prototype_analysis/captured-logs/phase16/phase16_live_logcat.txt:18917-18928`):

```
16:33:04.930  ABBAFF02 notification #4: 151 bytes: … (DTLS ClientHello; identity MD-A376B03C-6AF89570)
16:33:04.941  BlueBitGG_Tls: resolve called keyId='MD-A376B03C-6AF89570' nodeKey=D8:9E:C1:BA:3D:E4
16:33:04.941  BlueBitGG_Tls: resolve result: NOT FOUND
16:33:04.941  BlueBitGG_Tls: resolve called keyId='MD-A376B03C-6AF89570' nodeKey=D8:9E:C1:BA:3D:E4
16:33:04.941  BlueBitGG_Tls: resolve result: NOT FOUND
16:33:04.941  GoldenGate: GG_DtlsProtocol_ResolvePsk WARNING: GG_TlsKeyResolver_ResolveKey failed to resolve key with identity 4D442D41333736423033432D3641463839353730 …
16:33:04.942  BlueBitGG_TxSink: Native TX: 44 bytes: … 15FEFD … 0273        <-- fatal alert, desc 0x73 = 115 unknown_psk_identity
16:33:04.946  BlueBitGG_Stack: onDtlsStatusChange state=3 code=-10601 pskIdentity=<redacted>
16:33:04.946  BlueBitGG_Stack: onDtlsStatusChange state=0 code=0
16:33:04.946  BlueBitGG_Stack: onDtlsStatusChange state=1 code=0
…
16:33:05.234  BlueBitGG_Tls: resolve called keyId='BOOTSTRAP' nodeKey=D8:9E:C1:BA:3D:E4
16:33:05.244  BlueBitGG_Stack: onDtlsStatusChange state=2          <-- SESSION
```

- Hex `4D442D41333736423033432D3641463839353730` = ASCII `MD-A376B03C-6AF89570` (a **device identifier**, not a secret).
- Resolver identity log also present at `phase16_experiment_window_log.txt:133-181` and `phase14_experiment_window.txt:140-193` — **identical device and identity across phases**.
- The mechanics are documented in `BlueBitApp/dtls_error_minus_10601_investigation.md` (state 3, code `-10601` = `GG_ERROR_TLS_UNKNOWN_IDENTITY`; server-side resolver miss).

### 2.5 What is *not* known (task-1 gap)

- The **secret** for `MD-A376B03C-6AF89570` (never captured, never claimed).
- Whether the tracker will *ever* accept a non-BOOTSTRAP identity from BlueBit, and whether a MobileData session is a precondition for `bond/control`.
- Whether `MD-A376B03C-6AF89570` is stable per device (it is stable across the Phase 14/16 runs and the earlier capture for this device, but n is small).

---

## 3. Task 2 — `bond/control` vs `pair/display` request-path comparison

### 3.1 Android-side path (official Fitbit APK, decoded)

| Step | `bond/control` | `pair/display` | Shared? |
|------|---------------|----------------|---------|
| Endpoint selection | `CoapEndpointMapper.map(address)` | `CoapEndpointMapper.map(address)` | **Yes** (`CoapBondControlResource.java`; `PairCommandsHandler.java`; `DefaultCoapEndpointMapper.java`) |
| DTLS session | same `CoapEndpoint` → same session | same `CoapEndpoint` → same session | **Yes** |
| PSK identity | whatever the tracker negotiated (BOOTSTRAP in BlueBit) | identical | **Yes** |
| Method / builder | `PUT`, `OutgoingRequestBuilder(path, PUT)` | `PUT`, `OutgoingRequestBuilder(path, PUT)` | **Yes** |
| Body | `BondControl.Control{operationTimeout}` (`BondControlRequestBuilder.java`) | `PairDisplay.Display{command = SHOW_CODE \| CLEAR_CODE}` (`PairDisplayRequestBuilder.java`) | differs (protobuf) |
| URI path | `"bond/control"` (`CoapBondControlResourceKt`) | `"pair/display"` (`PairCommandsHandlerKt:8`) | differs |
| Response handling | `CoapResponseUtils.assertOkFor` → `getResourceResponseException` | same | **Yes** |
| Per-resource credential / key-id / handshake step | none | none | **Yes** |

**`Endpoint.getDtlsKeyId()` does not select a per-resource key.** It merely *reports* the session's PSK identity when the DTLS state is `SESSION`, else `null` (`Decoded/…/bindings/coap/CoapEndpoint.java:361-373`). It has **no other call site** in the decoded sources (grep `getDtlsKeyId` across `Decoded/…/com/fitbit` → only the definition).

### 3.2 BlueBit-side request construction

`FitbitGoldenGateDiagnostic.kt` builds both requests through the same code (`OutgoingRequestBuilder(path, method) … .forceNonBlockwise(true)`, `~:633-635`) and sends via the same submission path (`sendToBle` / `submitGattlinkWrite`, `~:1114-1219`). The only per-request differences are the path string and the 2-byte payload.

### 3.3 DTLS + CoAP role sequence (measured shape)

```
Tracker (GG node / DTLS CLIENT)                         Phone = BlueBit (GG hub / DTLS SERVER)
        |  (1) ClientHello  (PSK-AES128-CCM offered)          |
        |----------------------------------------------------->|
        |  (2) ServerHello + ServerHelloDone                   |
        |<-----------------------------------------------------|
        |  (3) ClientKeyExchange  identity = MD-XXXXXXXX        |
        |----------------------------------------------------->|
        |        resolver walk: Hello? no.  Bootstrap? no.  →  NOT FOUND
        |  (4) Alert(fatal) unknown_psk_identity (115)         |
        |<-----------------------------------------------------|
        |        GG_TlsKeyResolver_ResolveKey failed → state=3 (-10601) → reset
        |  (5) ClientHello  (retry)                            |
        |----------------------------------------------------->|
        |  (6) ServerHello + ServerHelloDone                   |
        |<-----------------------------------------------------|
        |  (7) ClientKeyExchange  identity = "BOOTSTRAP"       |
        |----------------------------------------------------->|
        |        resolver walk: Bootstrap? YES (16 bytes)      |
        |  (8) ChangeCipherSpec + Finished  (both directions)  |
        |<====================================================>|   DTLS SESSION (state=2)
        |  (9)  CoAP PUT /pair/display   →  2.04 Changed       |   (watch shows a 4-digit code)
        | (10)  CoAP PUT /bond/control   →  4.01 Unauthorized  |   (body 0, options 0)
```

Steps (1)-(8) are evidenced by `dtls_handshake_summary.csv`; (9)-(10) by `prototype_analysis/BLE_CONNECTION_INVESTIGATION.md:848-851`.

### 3.4 Discrepancy flag (raw evidence wins)

`inspire3_protocol_analysis_report.md:160-162` says *"the tracker does not accept the `MD-A2CB0B8E-6AE5D947` PSK identity"*. The raw CSV shows the rejecting alert is **`phone->tracker`** (`dtls_handshake_summary.csv:8`), i.e. the **phone/server** rejected the tracker's identity — the same direction as BlueBit's own `-10601`. The BlueBit analysis should use the raw direction. (Not a change to any file; a note for correctness.)

---

## 4. Task 3 — Is `4.01 → RF_ERR_KEY_EXPIRED` a mere mapping, or evidence of an expired credential?

**Result: a mere mapping — proven at source level, and it additionally *masks* any device-supplied detail.**

`Decoded/…/goldengate/CoapResponseUtils.java:174-186`:

```java
private final int getResponseCode(ResponseCode responseCode, ExtendedError extendedError) {
    int intCode = getIntCode(responseCode);
    if (intCode == getIntCode(ResponseCode.Companion.getServiceUnavailable()))   // 5.03
        return AirlinkErrorCode.RF_ERR_STREAM_SYSTEM_NOT_READY.getErrorCode();
    if (intCode == getIntCode(ResponseCode.Companion.getUnauthorized()))         // 4.01
        return AirlinkErrorCode.RF_ERR_KEY_EXPIRED.getErrorCode();               // 5378, hard-coded
    if (extendedError == null) return 0;
    return extendedError.getCode();                                             // only for non-4.01/5.03
}
```

Key points:

- `4.01` is mapped **unconditionally** to `RF_ERR_KEY_EXPIRED` = `5378` = `0x1502`; the name is reused from the Airlink firmware enum (`Decoded/…/fbcomms/ota/AirlinkErrorCode.java`).
- For `4.01`, a device `ExtendedError` **would be ignored** — the app cannot surface the firmware's real reason for a 4.01 through this path.
- Phase 16 observed `body = 0`, `Options = 0` for `bond/control` → no `ExtendedError` was present anyway.

**Conclusion:** `RF_ERR_KEY_EXPIRED` carries **no** credential-specific information. Any argument that `4.01` means "the key is expired" is unsupported by the code. Confidence: **high** (source + observed response shape).

---

## 5. Task 4 — Inventory of reusable local-harness components

### 5.1 Already in the repo

| Component | Path | What it gives us |
|-----------|------|------------------|
| CoAP codec + blockwise reassembly (pure Python) | `prototype_analysis/offline_poc/coap_parser.py`, `blockwise_reassembler.py`, `sync_dump_reconstructor.py`, `test_offline_poc.py` | RFC 7252 parser/emitter + RFC 7959 block logic; golden tests |
| Offline Gattlink/IPv4/DTLS parser | `analyse_inspire3_capture.py` (+ `successful_sync_exchange.tsv`, `inspire3_analysis_output/*`) | Framing → IPv4 → UDP/5684 → DTLS record parser; PSK-identity extraction |
| Bootstrap-decrypted CoAP samples | `inspire3_decryption_analysis/decrypted_coap_samples.csv`, `sample_frame*.bin`, `validate_reconstruction.py` | Real request/response vectors incl. `4.01`, `2.04`, `2.05`, `2.31` |
| Backend HTTP mock | `BlueBitApp/mock-server/fitbit_mock_server.py` (+ `EXPERIMENT_REPORT.md`, `README.md`) | Mock of `validate.json`/`pair.json`/`ack.json`/`sync*.json`; for the *server-mediated* hypothesis |
| Existing server-then-bond experiment | `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/ServerPairingExperiment.kt` | Already-encoded "does a server call change `bond/control`?" experiment (live path) |
| Serializer harness (JVM) | `BlueBitApp/app/src/desktopTest/…/GattlinkTxSerializerTest.kt` | Fake transport; one-at-a-time write invariants |
| Probe state-machine tests | `app/src/desktopTest/…/DtlsBootstrapProbeTest.kt`, `CoapReadOnlyProbeTest.kt` | DTLS-state/CoAP-probe logic on JVM |
| Other JVM fakes | `BleServiceDataTest.kt`, `BlueBitDeviceMatcherTest.kt`, `PairedDeviceCoordinatorTest.kt` (`FakeProvider`) | BleConnection/BLE-service fakes |
| Client protocol components | `BootstrapTlsKeyResolver.kt`, `TlsKeyResolverRegistry.kt`, `TlsKeyResolver.kt`, `DtlsProbeStateMachine.kt`, `CoapEndpoint.kt`, `OutgoingRequestBuilder`, `SingleCoapResponseListener`, `CoapReadOnlyProbe.kt`, `DtlsBootstrapProbe.kt` | The client code a harness should exercise |

### 5.2 Structural constraint (decisive for harness design)

- `BlueBitApp/app/src/androidMain/jniLibs/` contains **only `arm64-v8a/libxp.so`**. There is **no** x86/x86_64 native library.
- The GoldenGate bindings live under **`androidMain`** only; there is **no `desktopMain` binding and no desktop native library**.

⇒ The **native** GG/DTLS/CoAP stack **cannot run on this workstation**, and **cannot run on a standard x86_64 Android emulator**. Any "run the real client offline" harness needs an **ARM Android target** and a test build — both currently out of scope.

---

## 6. Task 5 — Proposed local DTLS/CoAP test harness

### 6.1 Design principle

Split the problem by what each layer can actually exercise:

- **Layer L1 — Interpreter/mapping (runnable now, offline, JVM + Python).** Exercised against captured or synthetic CoAP messages: `4.01 → 5378` masking, `2.04/2.01/2.05/2.31` handling, `ExtendedError` passthrough for non-4.01 codes, and the resolver walk (`Hello → Bootstrap`, `MD-* → NOT FOUND`).
- **Layer L2 — DTLS-state machine + transport invariants (runnable now, JVM).** `DtlsProbeStateMachine`, `GattlinkStartupSequence`, `GattlinkTxSerializer` against fakes: the `state=1 → 3(-10601) → 0 → 1 → 2` reset/recovery, and one-at-a-time writes.
- **Layer L3 — Full native stack (NOT runnable now).** Requires ARM Android + test build (`isNode=false` hub + loopback `TxSink`/`RxSource`), exercising the real `libxp.so`, resolver chain and `CoapEndpoint`.

The **recommended** deliverable is a **dual-role synthetic simulator** that implements the *protocol shape* exactly (Gattlink prefix → IPv4 → UDP:5684 → DTLS-PSK → CoAP), plus JVM conformance tests against L1/L2. It is an **oracle and regression fixture**, not a tracker emulator.

### 6.2 Proposed harness architecture (Layer L1/L2, offline)

```
prototype_analysis/phase17/                     (NEW, isolated; no existing file touched)
├─ README.md                                    (how to run; what it can/cannot prove)
├─ synthetic_ids.py                             BOOTSTRAP + hello (public) + SYNTHETIC MD (marked)
├─ mock_stack/
│   ├─ gattlink_framing.py                      prefix byte (0x00-0x7F / 0x80 / 0x81) + IPv4/UDP:5684
│   ├─ dtls_psk.py                              DTLS 1.2 PSK-AES128-CCM (stdlib or pinned mbedtls)
│   ├─ roles.py                                 server=hub(phone) / client=node(tracker)
│   └─ coap_router.py                           resource→policy table (the "mock firmware")
├─ scenarios/
│   ├─ s1_bootstrap_fallback.py                 MD-* unknown → 115 → BOOTSTRAP → SESSION
│   ├─ s2_resource_policy.py                    pair/display→2.04 ; bond/control→4.01
│   ├─ s3_md_session.py                         (synthetic) MD identity → session → policy variant
│   └─ s4_extended_error.py                     4.01 + ExtendedError(code=X) → assert masking
├─ vectors/                                     copies-by-reference of offline_poc + decrypted samples
└─ tests/
    ├─ test_framing.py                          round-trip vs captured frames
    ├─ test_resolver_walk.py                    Hello/Bootstrap/MD resolution decisions
    └─ test_mapping.py                          CoAP code → app code (4.01→5378; masking)
```

JVM side (new files under `BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/phase17/`):

```
Phase17ResolverWalkTest.kt     Hello→Bootstrap chain; MD-* returns null; records requested ids
Phase17MappingTest.kt          replica of getResponseCode rules (4.01→5378, 5.03→…, else ExtendedError)
Phase17DtlsResetTest.kt        DtlsProbeStateMachine: 1 → 3(-10601) → 0 → 1 → 2 with a fake driver
```

> The JVM mapping test **re-encodes the documented rule** from `CoapResponseUtils.java`; it documents/guards behaviour and does not purport to be the native code (which is not on the JVM classpath).

### 6.3 Synthetic identities (examples; clearly labelled, never the real key)

| Identity (key id) | Key | Provenance |
|---|---|---|
| `"BOOTSTRAP"` | `810654E336ADCAB0A03C60F74AA0B6FB` | public hard-coded value (same in APK and BlueBit) |
| `"hello"` | `000102…0F` | public hard-coded value |
| `"MD-00000000-00000000"` | e.g. `A5A5…A5` (16 × 0xA5) | **SYNTHETIC** — a clearly fake identity/key used only to drive the harness |

**Rule:** the real `MD-A376B03C-6AF89570` identity may be *replayed as a string* (it is a non-secret identifier already in repo evidence), but its key is **never** fabricated and **never** claimed. The harness must assert that BlueBit's real chain returns `null` for it.

### 6.4 Test matrix

| ID | Scenario | Setup (synthetic) | Expected observation | Layer |
|----|----------|-------------------|----------------------|-------|
| T1 | Bootstrap fallback | tracker client offers `MD-00000000-00000000` then `BOOTSTRAP` | mock server emits alert 115; state machine goes 1→3(-10601)→0→1→2 | L2 (+L1c) |
| T2 | Resolver walk | ids `hello`, `BOOTSTRAP`, `MD-00000000-00000000` | resolves `hello`/`BOOTSTRAP`; `MD-*` → null; requested ids recorded | L1 |
| T3 | Resource policy (BOOTSTRAP session) | `PUT /pair/display` → 2.04; `PUT /bond/control` → 4.01 | client reports pair OK; bond → `RF_ERR_KEY_EXPIRED(5378)` | L1 |
| T4 | Resource policy (synthetic-MD session) | same two PUTs | same client behaviour → proves the *client path* is identity-independent | L1 |
| T5 | ExtendedError masking | `4.01` + `ExtendedError(code=0x1234)` | app still returns `5378` (mask proven) | L1 |
| T6 | Non-4.01 ExtendedError | `4.03` / `5.00` + `ExtendedError(code=X)` | app returns `X` | L1 |
| T7 | Serializer invariants (regression) | rapid multi-write | one outstanding write at a time; byte-identical payloads | L2 |
| T8 | Field-fidelity | replay captured `pair/display` / `bond/control` request bytes | mock decode == captured decode | L1 |

### 6.5 Layer L3 (future, not authorised now)

Two `Stack` instances on a single ARM Android target over a loopback transport: one `isNode=false` (BlueBit hub/server, real resolver chain) and one `isNode=true` (node/client offering a chosen identity). This is the **only** design that exercises the **real** `libxp.so` resolver + `CoapEndpoint` + native DTLS. Needs: an ARM target, a test-only APK, and explicit approval. Suggested synthetic identity/key only.

---

## 7. Task 6 — What the harness can and cannot prove

**Can prove (offline):**

- BlueBit's `bond/control` and `pair/display` requests are structurally identical except path + body (§3).
- The app maps `4.01 → 5378` deterministically and **discards** device `ExtendedError` for `4.01` (§4).
- BlueBit's resolver chain resolves `hello`/`BOOTSTRAP` and returns `null` for any `MD-*` (§2.3).
- The `-10601 → reset → BOOTSTRAP → SESSION` sequence is exactly reproducible (§2.4).
- Regression coverage for the serializer and DTLS-state machine.

**Cannot prove:**

- The tracker firmware's actual authorization policy for `bond/control`.
- Whether a **real** MobileData-authenticated session would let `bond/control` succeed.
- The tracker's provisioning/bonding state, or whether the real `MD-*` key is required.
- Whether real firmware would send an `ExtendedError` (and thus what the real reason is).
- The remote policy cause of the observed `4.01`.

---

## 8. Task 7 — Minimum extra evidence to distinguish the four failure classes

| Class | Statement | What would confirm/deny it |
|-------|-----------|----------------------------|
| **A. Missing/incorrect client credential** | Tracker presents `MD-*`; BlueBit has no key | Offline: answer already known (`NOT FOUND`). The open part is whether possession of the *real* key is required |
| **B. Server trust/identity failure** | BlueBit authenticates the tracker only via BOOTSTRAP; cannot assert a stronger identity | Offline: resolver chain is `Hello→Bootstrap` only (source). Whether a stronger session is *required* is tracker policy |
| **C. Resource-specific authorization** | `bond/control` needs something `pair/display` does not | Offline: re-derive the tracker's resource→status table from the existing bootstrap-decrypted capture; check whether *any* resource beyond `pair/display` is allowed under BOOTSTRAP |
| **D. Required sequencing/pairing state** | A pre-`bond` exchange (e.g. server-mediated `/sync/dump → backend → /sync/response`) or a device pairing state is required | Offline: inspect the official capture for the presence/order of `bond/control` vs the sync/pair exchanges and the identity in force at each step |

### 8.1 Minimum extra evidence — ordered, offline-first

- **E1 (offline, no device) — resource→status authorization table.** From `inspire3_decryption_analysis/decrypted_coap_samples.csv` + `blockwise_reassembler.py`, tabulate every resource × method × status, and the DTLS identity active for each. *Directly discriminates C from D* and establishes what BOOTSTRAP authorizes.
- **E2 (offline, no device) — does `bond/control` appear in the official session, and under which identity?** Search the decrypted samples / capture for `bond/control` and correlate with the handshake identity in force. If the official app's `bond/control` only ever occurs under a MobileData session ⇒ **C (or B)**. If it occurs after a specific exchange under BOOTSTRAP ⇒ **D**.
- **E3 (offline, no device) — provisioning state inference.** The tracker presents `MD-A376B03C-6AF89570` (a MobileData identity) *before* any pairing with BlueBit ⇒ the device is **already provisioned**. Record this as evidence for the composite hypothesis; it constrains D.
- **E4 (proposal; read-only instrumentation, needs approval) — resolver identity recorder.** Have `TlsKeyResolver.resolve` persist the *requested* identity + resolution result per attempt (identity is non-secret; any unexpected key bytes must be truncated/redacted). Removes reliance on the redacted `pskIdentity=<redacted>` and makes A/B conclusions from live logs **record-grade**.
- **E5 (proposal; requires a later, separately-approved live run) — synthetic MobileData probe.** Register a **synthetic** `MD-…` resolver (arbitrary key) for a chosen fake identity and observe whether the tracker completes a session. If it *fails the Finished*, that proves the tracker holds a **real, device-specific key** (rules B/A in). If it *succeeds*, the identity is not device-bound and the block is resource-layer (C/D). **Never** claims a real key; strictly synthetic.

**Recommended minimal next action:** **E1 + E2** (purely offline, uses only existing captured evidence, modifies nothing). They are the cheapest and most discriminating, and they directly test hypotheses C and D without touching the device or the tree.

---

## 9. Open questions and confidence

| # | Claim | Confidence | Basis |
|---|-------|-----------|-------|
| 1 | Phone = DTLS server; tracker = DTLS client | **High** | `isNode=false` in 3 BlueBit classes + JNI mapping doc + captured handshake |
| 2 | Tracker's default identity is `MD-*`; BlueBit falls back to BOOTSTRAP | **High** | Raw logcat + captured CSV, reproducible |
| 3 | BlueBit has no MobileData credential/resolver | **High** | Source grep + resolver chain |
| 4 | `bond/control` vs `pair/display` differ only by path/body on the app side | **High** | Decoded + BlueBit sources |
| 5 | `4.01 → RF_ERR_KEY_EXPIRED` is a label, and masks ExtendedError | **High** | `CoapResponseUtils.java:174-186` |
| 6 | Block is tracker-side per-resource authorization under BOOTSTRAP | **Medium-High** | Official capture: `/md/3d02` and `/sync/config` also `4.01` under bootstrap |
| 7 | A MobileData session is required/preferred for `bond/control` | **Medium** | Consistent with MD identities + provisioning; not yet directly observed |
| 8 | Server-mediated/provisioning-sequence requirement (Gadgetbridge) | **Medium** | `Gadgetbridge_Investigation_Report.md`; `ServerPairingExperiment.kt` hypothesis |
| 9 | Tracker is already provisioned (prior account pairing) | **Medium** | Presents `MD-*` before BlueBit pairing; stable identity |
| 10 | `RF_ERR_KEY_EXPIRED` implies the key is really expired | **Ruled out** | §4 |

---

## 10. Proposed safe minimal next action (for approval)

1. **Do E1 + E2 offline** (new script(s) under `prototype_analysis/phase17/`, reading only existing captures):
   - Build the resource × method × status × DTLS-identity table from the bootstrap-decrypted samples.
   - Determine whether `bond/control` appears in the official session and under which identity.
2. **Then** implement the Layer-L1/L2 harness (§6) as pure-JVM + Python with synthetic identities — no device, no native stack.
3. **Only after explicit approval**, consider the read-only resolver-identity instrumentation (E4) and, separately, a Layer-L3 / E5 run on an ARM target — each with its own pre-registration.

**Nothing in items 2-3 proceeds without a separate, explicit go-ahead.** No device action is proposed for item 1.

---

## 11. Constraints compliance (this document)

- Kinds of work performed: file reads, `grep`/`glob`, directory listing, `git rev-parse`/`git stash list`/`git status --porcelain`, hashing (planned). No device command, no build, no install, no ADB.
- No existing file modified; no captured evidence altered; the investigation report untouched.
- Working tree unchanged except the addition of this file (78 → 79 untracked/modified entries; the original 78 untouched). No commit/stash/reset/clean.
- No secret fabricated; only public hard-coded keys referenced and clearly-labelled synthetic values proposed.

---

## 12. Evidence index (inputs used)

| Artifact | Purpose |
|----------|---------|
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/FitbitGoldenGateDiagnostic.kt:535-558` | `Stack(..., isNode = false)`; hub/server role |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt`, `CoapReadOnlyProbe.kt` | Same `isNode=false`; probe logic |
| `BlueBitApp/app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolverRegistry.kt`, `TlsKeyResolver.kt`, `HelloTlsKeyResolver.kt`, `BootstrapTlsKeyResolver.kt` | Resolver chain + public keys |
| `BlueBitApp/app/src/androidMain/jniLibs/arm64-v8a/libxp.so` | Only ABI present (no x86/x86_64) |
| `BlueBitApp/reference_transport_api_report.md:172-173,473-474,497` | Hub=falseserver / Node=trueclient JNI mapping |
| `BlueBitApp/dtls_error_minus_10601_investigation.md` | `-10601` = `GG_ERROR_TLS_UNKNOWN_IDENTITY` |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/CoapResponseUtils.java:174-186` | `4.01 → 5378` mapping + ExtendedError masking |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bond/CoapBondControlResource.java`, `BondControlRequestBuilder.java`, `BondCommandsHandler.java` | Official `bond/control` path |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/commands/PairCommandsHandler.java`, `PairCommandsHandlerKt.java:8`, `PairDisplayRequestBuilder.java` | Official `pair/display` path |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/coap/CoapEndpoint.java:361-373` | `getDtlsKeyId()` = session identity report only |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/dtls/MobileDataTlsKeyResolver.java`, `MobileDataTlsKeyId.java` | Official per-device MD key store; expiry field |
| `inspire3_analysis_output/dtls_handshake_summary.csv` | Raw DTLS handshake + PSK identities + alert direction |
| `inspire3_protocol_analysis_report.md:23-24,130-173,225-234` | DTLS roles/cipher/identities |
| `prototype_analysis/PROTOCOL_PROTOTYPE_REPORT.md:18-86,163-177` | Bootstrap CoAP status table incl. `/md/3d02 → 4.01` |
| `prototype_analysis/BLE_CONNECTION_INVESTIGATION.md:775-800,848-877,3141-3143` | BlueBit role, resolver logging, `bond/control 4.01` / `pair/display 2.04` |
| `prototype_analysis/captured-logs/phase16/phase16_live_logcat.txt:18917-18928` and `phase16_experiment_window_log.txt:133-181` | MD NOT FOUND → alert 115 → -10601 → BOOTSTRAP FOUND |
| `prototype_analysis/captured-logs/phase14_experiment_window.txt:140-193` | Same behaviour in Phase 14 |
| `prototype_analysis/offline_poc/*` | Reusable CoAP codec + blockwise reassembly + tests |
| `inspire3_decryption_analysis/decrypted_coap_samples.csv`, `validate_reconstruction.py` | Golden CoAP vectors for the harness |
| `BlueBitApp/mock-server/fitbit_mock_server.py`, `EXPERIMENT_REPORT.md` | Backend HTTP mock (server-mediated hypothesis) |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/ServerPairingExperiment.kt` | Existing server-then-bond hypothesis |
| `BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/diagnostics/GattlinkTxSerializerTest.kt` | JVM fake-transport harness pattern |
| `Reports/bond-control-semantics-report.md`, `Reports/Gadgetbridge_Investigation_Report.md`, `Reports/inspire3-trust-authentication-evidence.md` | Prior analyses (note: the trust report predates DTLS-session success) |

Phase 16 evidence hashes are recorded in `prototype_analysis/BLE_CONNECTION_INVESTIGATION.md` §16.3 and were not re-hashed here.

---

## 13. Explicit non-actions

- No device was contacted; no ADB; no scan; no pair/unpair/bond; no reset.
- No APK was built, rebuilt, or installed; the pairing-code APK was not touched.
- No firmware/account/bond/persistent setting was changed.
- No existing source, capture, or report was modified.
- No real secret was derived, claimed, or fabricated; only public values and clearly-labelled synthetic values appear.
