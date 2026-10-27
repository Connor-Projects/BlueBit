# Phase 17 — E1/E2 Offline Results

**Status:** COMPLETE (offline analysis only)
**Date:** 2026-10-19
**Author:** BlueBit investigation
**Scope:** E1 + E2 from `PHASE17_AUTH_HARNESS_PLAN.md` §8.1. Read-only; no device, no build, no install, no network. Uses only evidence already in the repository.

---

## 1. Purpose

- **E1** — Build a rigorous `resource × method × status × direction` table from the already-decrypted bootstrap CoAP samples, to establish what the tracker authorizes under a BOOTSTRAP session.
- **E2** — Determine whether `bond/control` appears anywhere in the official captured session, and which DTLS PSK identity was in force.

These directly discriminate hypothesis **C** (resource-specific authorization under BOOTSTRAP) from hypothesis **D** (a required sequencing / pairing state), as defined in `PHASE17_AUTH_HARNESS_PLAN.md` §8.

---

## 2. Inputs (read-only, hashes recorded)

| Input | SHA-256 |
|---|---|
| `inspire3_decryption_analysis/decrypted_coap_samples.csv` | `4dc8c8a16a098c99325b09e0cbfcceaf03157de186601956133b4d12eab72d33` |
| `inspire3_decryption_analysis/reconstruction_outputs/dtls_handshake_summary.csv` | `9f1295ae86a006fb6c3624bb11ee5e302871e5fced430d956929b61686b26214` |
| `inspire3_analysis_output/dtls_handshake_summary.csv` (identical copy) | `9f1295ae86a006fb6c3624bb11ee5e302871e5fced430d956929b61686b26214` |
| `prototype_analysis/offline_poc/coap_parser.py` (reused, unmodified) | `533323c5d30fff0a97605958d1dcfac8b09cb56ca4dbe8b844568d5a30a75988` |

Frame numbering in `decrypted_coap_samples.csv` is the **inner reconstructed-PCAP** frame number (1..341), not the BLE ATT frame number. Bootstrap session per `DECRYPTION_REPORT.md`: handshake frames 5-8, app data frames 9-55, phone→tracker bootstrap responses up to frame 204; MobileData re-key begins at inner frame 56.

---

## 3. Method

New script `prototype_analysis/phase17/e1_e2_resource_auth_table.py` (SHA-256 `dd34222bd10bec0d5b7e6b2ee16154b869bc4f6bf7880b64fe085ef0a2e3987f`):

1. Reads every row of `decrypted_coap_samples.csv`.
2. Loads the referenced full message from `inspire3_decryption_analysis/sample_frame*.bin` (falls back to the CSV `first_128_hex` snippet if the `.bin` is absent).
3. Parses it with the **existing, unmodified** `offline_poc/coap_parser.py`.
4. Pairs each request to its response by CoAP token (RFC 7252), giving 37 completed transactions out of 74 messages.
5. Emits the resource table, a bond/control presence check, and identity/alert attribution from `dtls_handshake_summary.csv`.

No existing file was modified. Only new files were created under `prototype_analysis/phase17/`.

---

## 4. E1 result — tracker resource authorization under BOOTSTRAP

**37 transactions, 7 distinct resources.**

| resource | requester | responder | methods | statuses | n |
|---|---|---|---|---|---|
| `/events` | tracker | phone | `0.02 POST` | `2.04 Changed` | 1 |
| `/md/3d02` | tracker | phone | `0.02 POST` | **`4.01 Unauthorized`** | 2 |
| `/pair/display` | phone | tracker | `0.03 PUT` | `2.04 Changed` | 2 |
| `/sync/config` | phone | tracker | `0.01 GET` | **`4.01 Unauthorized`** | 1 |
| `/sync/dump` | phone | tracker | `0.01 GET` | `2.05 Content` | 28 |
| `/sync/dump?t=pair` | phone | tracker | `0.01 GET` | `2.05 Content` | 1 |
| `/sync/response` | phone | tracker | `0.03 PUT` | `2.04 Changed` \| `2.31 Continue` | 2 |

### 4.1 Tracker-as-CoAP-server view (the relevant one for `bond/control`)

Under the **BOOTSTRAP** session the tracker (responder) **allowed**:

- `/pair/display` → `2.04 Changed`
- `/sync/dump` (+ `?t=pair`) → `2.05 Content`
- `/sync/response` → `2.31 Continue` / `2.04 Changed`

…and **denied**:

- `/sync/config` → **`4.01 Unauthorized`**

**This is direct, independent evidence that the tracker enforces per-resource authorization within a BOOTSTRAP session**: the same session that accepts `/pair/display` returns `4.01` for `/sync/config`. It is not specific to `bond/control`.

### 4.2 Phone-as-CoAP-server view

- `/events` (tracker POST → phone) → `2.04 Changed`
- `/md/3d02` (tracker POST → phone) → **`4.01 Unauthorized`** ×2

The `/md/3d02` `4.01` is issued **by the phone** (the official app refusing the tracker's MobileData metadata push at that point), i.e. a different actor from the tracker-side `4.01` on `/sync/config` and on BlueBit's `/bond/control`. It shows `4.01` is a general "not authorized now" signal on both sides.

### 4.3 Direction discrepancy note (raw evidence wins)

`prototype_analysis/PROTOCOL_PROTOTYPE_REPORT.md:26` lists all `4.01` responses as `tracker -> phone`. The parsed raw evidence shows **one** `tracker→phone` (`/sync/config`) and **two** `phone→tracker` (`/md/3d02`). The raw decrypted messages take precedence.

---

## 5. E2 result — `bond/control` in the official session

**ANSWER: NO.** `bond/control` does not appear anywhere in the bootstrap-decrypted set.

- Messages whose path contains `bond` : **0**
- Messages whose path contains `control` : **0**
- Full set of observed paths: `/events`, `/md/3d02`, `/pair/display`, `/sync/config`, `/sync/dump`, `/sync/response`.

### 5.1 Identity attribution

DTLS PSK identities observed on the wire (`dtls_handshake_summary.csv`):

| BLE frame | direction | identity |
|---|---|---|
| 12184 | tracker→phone | `MD-A2CB0B8E-6AE5D947` |
| 12241 | tracker→phone | `BOOTSTRAP` |
| 13226 | tracker→phone | `MD-A376B03C-6AF89570` |

Alert observed: `BLE frame 12186 phone→tracker level=2 desc=unknown_psk_identity` (the phone rejecting the first MD identity).

All 74 decrypted CoAP messages lie in inner frames 9..204 = the **BOOTSTRAP** session window. The MobileData re-key begins at inner frame 56 and completes at inner frame 204; records after that are encrypted under MobileData keys and are not decryptable with the BOOTSTRAP PSK.

⇒ **Every CoAP message in this table was exchanged under the BOOTSTRAP PSK identity, not a MobileData identity.**

---

## 6. Interpretation

1. **Hypothesis C gains strong offline support.** Under a single BOOTSTRAP session the tracker selectively authorizes resources: `/pair/display` OK, `/sync/config` `4.01`. A resource-specific authorization policy exists and is exercised in practice. `bond/control` returning `4.01` under BOOTSTRAP is consistent with that policy.

2. **Hypothesis D is not settled but is constrained.** This capture is a **post-provisioning sync**: the tracker is already provisioned (it presents a MobileData identity and re-keys to MobileData), and the official app never needs to send `bond/control` here. So the capture cannot show the exact preconditions for `bond/control`; it only shows that the tracker already trusts the paired phone enough to allow sync resources.

3. **`/sync/config` is a concrete analogy.** The tracker returning `4.01` for `/sync/config` while accepting `/pair/display` in the same session is the closest observed analogue to `bond/control`. Both are "control/config" style resources; the tracker gates them.

4. **The `/md/3d02` `4.01` is the phone's policy, not the tracker's** — it must be quoted carefully to avoid conflating actors.

---

## 7. Updated hypothesis status (from `PHASE17_AUTH_HARNESS_PLAN.md` §9)

| # | Claim | Before | After E1/E2 |
|---|-------|--------|-------------|
| 6 | Block is tracker-side per-resource authorization under BOOTSTRAP | Medium-High | **High** — directly observed for `/sync/config` vs `/pair/display` |
| 7 | MobileData session required/preferred for `bond/control` | Medium | Unchanged (not observable here) |
| 8 | Server-mediated / provisioning-sequence requirement | Medium | Unchanged |
| 9 | Tracker already provisioned | Medium | **High** — presents MD identity and re-keys to MD in a post-pairing sync |

Classes **A** (missing credential) and **B** (server trust) remain as established: BlueBit cannot resolve the tracker's `MD-*` identity (source + logs). The open question is whether `bond/control` additionally requires a MobileData-authenticated session (**C/B**) or a specific pairing sequence (**D**).

---

## 8. What E1/E2 do and do not settle

**Settled (offline, high confidence):**

- The tracker enforces resource-level authorization under BOOTSTRAP (`/sync/config` → `4.01`, `/pair/display` → `2.04` in the same session).
- The official captured session contains **no** `bond/control`; it is a post-provisioning sync under BOOTSTRAP.
- All decrypted CoAP traffic is BOOTSTRAP-authenticated.

**Not settled:**

- Whether a real MobileData-authenticated session would permit `bond/control`.
- Whether `bond/control` requires a preceding provisioning/pairing exchange.
- The tracker's exact per-resource policy table (only 7 resources observed).

---

## 9. Recommended next offline step (no device) — "E6"

**Trace the official app's preconditions for invoking `bond/control`.** Because E2 shows the capture cannot answer it, the next best *offline* evidence is the decoded APK call graph:

- `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bond/BondCommandsHandler*.java` and `CoapBondControlResource.java`
- whatever state machine decides to call `bond/control` (grep for `BondCommandsHandler`, `bondControl`, `RF_ERR_KEY_EXPIRED`, `MobileDataTlsKeyId` usage)
- whether invocation is gated on a MobileData session identity, a pairing/token state, or the presence of a device key

This is pure source inspection, modifies nothing, and can directly test C vs D without touching the tracker. (E4/E5 from the plan remain device/instrumentation options that require separate explicit approval.)

---

## 10. Outputs and hashes

| Output | SHA-256 |
|---|---|
| `prototype_analysis/phase17/e1_e2_resource_auth_table.py` | `dd34222bd10bec0d5b7e6b2ee16154b869bc4f6bf7880b64fe085ef0a2e3987f` |
| `prototype_analysis/phase17/out/e1_message_table.csv` | `ce271659dbe794a9753b7c97a68c77bef986c62f47495db839e29c382129c8ae` |
| `prototype_analysis/phase17/out/e1_resource_table.csv` | `bfb4b39680aae17b1df635a58625d3ffc98998ee0e005e6401d2c6aca4bf6ed8` |
| `prototype_analysis/phase17/out/e1_summary.md` | `5bf62f789bb313b86ea7a8c2f52732cd823b2e9087fbedce3d08549b84e5d270` |
| `prototype_analysis/phase17/out/e2_bond_control_check.txt` | `c533eaf752486bc01513964b44af6702e5e187716585f25210cf0c8cdd753a79` |

---

## 11. Non-actions (this phase)

- No device contacted; no ADB, scan, pair/unpair, bond, or reset.
- No APK built/installed; pairing-code APK untouched.
- No firmware/account/bond/persistent setting changed.
- No existing tracked file modified (verified: modified tracked files are unchanged pre-existing entries from earlier phases; none under `phase17`).
- Working tree: `HEAD 7d2342fd…`, stash 0, status-entry count unchanged at 78 (new files live inside the already-untracked `prototype_analysis/` directory). Nothing committed/stashed/reset/cleaned.
- No real secret derived/claimed/fabricated.
