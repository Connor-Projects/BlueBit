# Phase 17 — Consolidated Summary

**Status:** COMPLETE (offline investigation only)
**Date:** 2026-10-19
**Author:** BlueBit investigation
**Scope:** Consolidates the Phase 17 plan and its two executed evidence steps (E1/E2 and E6) into a single answer, hypothesis table, and boundary statement. No device was contacted; no build/install; no existing file modified.

This document does not replace the detailed records — see:

- `PHASE17_AUTH_HARNESS_PLAN.md` — the plan, harness design, and test matrix.
- `phase17/E1_E2_RESULTS.md` — the resource×method×status table + `bond/control` presence check.
- `phase17/E6_BOND_CONTROL_PRECONDITIONS.md` — the official app's preconditions for invoking `bond/control`.

---

## 1. The question

Why does the Fitbit Inspire 3 return CoAP **`4.01 Unauthorized`** for `PUT bond/control` while `PUT pair/display` returns **`2.04 Changed`** — and what would it take for BlueBit to make `bond/control` succeed?

---

## 2. Evidence basis

| Step | Type | Input | Result |
|------|------|-------|--------|
| Plan | analysis | repo | `PHASE17_AUTH_HARNESS_PLAN.md` |
| E1 | offline data | `inspire3_decryption_analysis/decrypted_coap_samples.csv` (+ `sample_frame*.bin`) | 37 transactions, 7 resources, per-resource status |
| E2 | offline data | same + `reconstruction_outputs/dtls_handshake_summary.csv` | `bond/control` **absent**; all CoAP under BOOTSTRAP |
| E6 | source inspection | decoded Fitbit APK (`Decoded/`, gitignored) | owner proof = server-issued MobileData PSK; `bond/control` is pairing-only |

No live experiment was performed. Everything below is either directly observed in captured/decrypted data or read from decompiled sources.

---

## 3. Consolidated findings

- **F1 — Role inversion.** BlueBit builds `Stack(..., isNode = false)` ⇒ GoldenGate **hub = DTLS server**; the Inspire 3 is the GG **node = DTLS client** (`reference_transport_api_report.md:172-173`). The **tracker chooses and presents the PSK identity**.
- **F2 — Resolver chain mismatch.** BlueBit's chain is `Hello → Bootstrap`. The official app's chain is `Hello → Bootstrap → **MobileData**` (`defpackage/nnw.java:269` registers `MobileDataTlsKeyResolver` at init). BlueBit has no `MobileDataTlsKeyResolver` at all.
- **F3 — MobileData PSK is server-generated and account+device-scoped.** The key is `DeviceAuthCredentials.authSubKey` + `nonce`(expiry), parsed from the backend response `{"btleClientAuthCredentials":{"authSubKey":…,"nonce":…}}` (`qoo.java:38,42`), fetched from `user/-/devices/tracker/generateBtleClientAuthCredentials` (serial-keyed; `noi.java:155-162`), stored encrypted as `trackerAuthCredentials.json` (`qoo.java:78`) and held as an Android-keystore `SecretKey` served to the resolver via `qwi`/`plj` (`qwi.java:23-29`, `MobileDataTlsKeyResolver.java:30-36`). A third-party app that does not own the device on an account **cannot obtain it**.
- **F4 — BOOTSTRAP enforces per-resource authorization.** In one BOOTSTRAP session the tracker returns `2.04 Changed` for `PUT /pair/display` but **`4.01 Unauthorized`** for `GET /sync/config` (E1). So resource-level, session-identity-based authorization is directly observed and is not unique to `bond/control`.
- **F5 — `bond/control` is a pairing-time call.** It is reached only via `GoldenGateCommandHandler.createNewBond` (`:517`) → `BondCommandsHandler.create`, invoked from the pairing flow (`pzv.java:32,54`). The handler checks the OS bond state, **removes any existing bond**, sends CoAP `PUT bond/control` (`CoapBondControlResource.java:53,119`), then performs the Android `createBond`.
- **F6 — The app models device ownership.** `TrackerBondState` = `{NOT_BONDED, BONDED_TO_CURRENT, **BONDED_TO_OTHER**, BONDED_NOT_SECURE}`.
- **F7 — `4.01 → RF_ERR_KEY_EXPIRED` is an Android-side artifact.** `CoapResponseUtils.java:174-186` maps `4.01` unconditionally and ignores any device `ExtendedError`; it is **not** evidence of a genuinely expired key.
- **F8 — The official capture cannot show the policy directly.** `bond/control` appears nowhere in the decrypted session (E2); that session is a **post-provisioning sync** in which the tracker presents a MobileData identity and re-keys to MobileData. All decrypted CoAP is BOOTSTRAP-authenticated.

---

## 4. Hypothesis table (updated)

Classes: **A** missing/incorrect tracker credential · **B** server trust/identity failure · **C** resource-specific authorization · **D** required sequencing/pairing state.

| # | Claim | Pre-Phase-17 | Final | Basis |
|---|-------|--------------|-------|-------|
| A | BlueBit lacks a credential the tracker expects | Medium | **High** | BlueBit resolver has no MobileData; every session `MD-* → NOT FOUND` |
| B | Server (phone) trust/identity failure | Low | Low | BlueBit *is* the DTLS server; identity selection is the tracker's side |
| C | Tracker authorizes per-resource by session identity | Medium | **High** | E1: `/sync/config` `4.01` vs `/pair/display` `2.04` in one BOOTSTRAP session |
| D | Requires a pairing/pairing-state sequence | Medium | **Medium-High** | E6: `bond/control` is pairing-only; `TrackerBondState.BONDED_TO_OTHER`; credential is server-mediated |
| — | Tracker is already provisioned/owned | Medium | **High** | Presents `MD-A376B03C-6AF89570`; re-keys to MobileData |
| — | `4.01` means a really expired key | — | **Ruled out** | F7 |

**Composite explanation (best supported):** BlueBit cannot present the account+device-scoped MobileData credential, so it can only ever establish a **BOOTSTRAP** session. On such a session, for an **already-owned** tracker, `bond/control` is refused with `4.01`, while `pair/display` is permitted. This is **A + C**, with a server-mediated-provisioning component (**D**); the operative cause is the missing server-issued credential, not an ordering bug, and not a real key expiry.

---

## 5. Direct answer to the question

`bond/control` and `pair/display` travel over the **same** CoAP endpoint/DTLS session; only the URI and protobuf body differ. The tracker therefore distinguishes them by **resource policy under the current session identity**. BlueBit's session is BOOTSTRAP-only (because the MobileData PSK is server-issued to the owning account), and the tracker's policy grants `pair/display` but denies `bond/control` (and `/sync/config`) on such a session for an already-owned device.

---

## 6. Boundary — what is and is not knowable offline

**Established offline (high confidence):**

- The mechanism is session/owner trust, exercised per-resource (F2–F5).
- The MobileData PSK is a backend-issued, account+device credential (F3).
- The official capture contains no `bond/control` and is BOOTSTRAP-authenticated (F8).
- `RF_ERR_KEY_EXPIRED` is an artifact (F7).

**Not knowable offline:**

- Whether a BOOTSTRAP `bond/control` succeeds on a **not-yet-owned** device (the "fresh device" prediction), since it requires a provisioning run.
- The tracker's complete per-resource policy table (only 7 resources observed).
- Whether the gate is account ownership, OS bond state, or both.
- Any successful `bond/control` under a real MobileData session (needs the server-issued key).

---

## 7. Discriminating experiments (proposed — none run)

| ID | Experiment | Needs | Can it settle it? |
|----|-----------|-------|-------------------|
| L1/L2 | Pure-JVM + Python synthetic protocol oracle (plan §6) | nothing (offline) | Exercises BlueBit's code paths; **cannot** obtain owner trust |
| E4 | Read-only resolver-identity instrumentation on device | explicit approval | Confirms which identity is offered/selected at runtime |
| E5 | Synthetic MobileData probe on device | explicit approval | Shows the DTLS `Finished` failure against the device-held key; **never** claims a real key |
| — | Factory-reset device + BOOTSTRAP `bond/control` | **device reset — currently forbidden** | Directly tests the "unowned device" prediction |

---

## 8. Deliverables index (SHA-256)

| Artifact | SHA-256 |
|----------|---------|
| `prototype_analysis/PHASE17_AUTH_HARNESS_PLAN.md` | `99025a72870d02bcd2ea94d5c764dd7bb60c4837204dca68e5a42a24765d7242` |
| `phase17/E1_E2_RESULTS.md` | `dc1ae09cfea4ff56455af4f034028d90d82d6bb2332bda82a7d8e7f286723e6e` |
| `phase17/E6_BOND_CONTROL_PRECONDITIONS.md` | `d6afe8bb42c7209a49b602d3adece43a2002c396734571f0fc024e6146d85cb4` |
| `phase17/PHASE17_CONSOLIDATED_SUMMARY.md` | (this file) |
| `phase17/e1_e2_resource_auth_table.py` | `dd34222bd10bec0d5b7e6b2ee16154b869bc4f6bf7880b64fe085ef0a2e3987f` |
| `phase17/out/e1_message_table.csv` | `ce271659dbe794a9753b7c97a68c77bef986c62f47495db839e29c382129c8ae` |
| `phase17/out/e1_resource_table.csv` | `bfb4b39680aae17b1df635a58625d3ffc98998ee0e005e6401d2c6aca4bf6ed8` |
| `phase17/out/e1_summary.md` | `5bf62f789bb313b86ea7a8c2f52732cd823b2e9087fbedce3d08549b84e5d270` |
| `phase17/out/e2_bond_control_check.txt` | `c533eaf752486bc01513964b44af6702e5e187716585f25210cf0c8cdd753a79` |
| `phase17/out/e6_source_hashes.txt` | `323e8610e2395a5a3a92280d6634a2c30dc6cb705b8fa60aa7c5e8a7cb44cc71` |

**Inputs (unchanged):** `decrypted_coap_samples.csv` `4dc8c8a1…2d33`; `reconstruction_outputs/dtls_handshake_summary.csv` `9f1295ae…6214`; `offline_poc/coap_parser.py` `533323c5…5988`.

---

## 9. Constraints compliance

- Offline only: no device, no ADB, no scan, no pair/unpair/bond, no reset.
- No APK build/install; the pairing-code APK is untouched.
- No firmware/account/bond/persistent setting changed.
- No existing source file, captured evidence, or investigation report modified; only new files under `prototype_analysis/phase17/` plus the pre-existing plan file.
- No secret derived, claimed, or fabricated. Structural field names (`authSubKey`, `nonce`) are described; no key value is produced. Device identifiers already present in repo evidence are treated as non-secret.
- Working tree preserved: `HEAD 7d2342fd…`, stash 0, status-entry count **78** (new files live inside the already-untracked `prototype_analysis/` directory). Nothing committed/stashed/reset/cleaned.
