# BlueBit Protocol Prototype Report

**Date:** 2026-10-17  
**Capture:** Fitbit Inspire 3 BLE -> IPv4/UDP/DTLS/CoAP session  
**Scope:** Reconstruct the decrypted CoAP exchanges, build an offline proof of
concept, and design the smallest live bootstrap test. The MobileData session is
identified but not implemented.

## 1. CoAP exchange reconstruction

The decrypted bootstrap session contains **74 CoAP messages**: 37 requests and
37 responses. All messages use CoAP version 1, a 4-byte token, and
Confirmable/Acknowledgement types. Response messages do **not** repeat
URI-Path options; matching is by token.

### 1.1 Message types and codes observed

| Code | Name | Count | Direction |
|------|------|-------|-----------|
| 0.01 | GET | 30 | phone -> tracker |
| 0.03 | PUT | 4 | phone -> tracker |
| 0.02 | POST | 3 | tracker -> phone |
| 2.05 | Content | 29 | tracker -> phone |
| 2.04 | Changed | 4 | tracker -> phone |
| 2.31 | Continue | 1 | tracker -> phone |
| 4.01 | Unauthorized | 3 | tracker -> phone |

### 1.2 `/sync/config` — 4.01 Unauthorized

- **Request (frame 12):** `GET /sync/config`, token `87aacad0`, MID `0x0001`.
- **Response (frame 14):** `4.01 Unauthorized`, same token, MID `0x0001`.

The capture gives the plaintext reason indirectly: the bootstrap session has
not yet completed the display-pairing and metadata handshake that the tracker
expects before exposing sync configuration. The first operations are
`/pair/display`, `/events`, and `/md/3d02`; only afterwards does the tracker
accept `/sync/dump` and finally `/sync/response`. `/sync/config` is attempted
early and rejected.

### 1.3 `/sync/dump` — block-wise transfer

`/sync/dump` dominates the bootstrap conversation. The capture shows **two
separate `/sync/dump` operations**:

1. **Initial `GET /sync/dump?t=pair` (frame 19)**  
   - Response (frame 20): `2.05 Content`, Block2 block #0, `M=0`, size 1024,  
     payload 165 bytes. This small transfer completes immediately.

2. **Main `GET /sync/dump` block-wise transfer**  
   - 29 response blocks numbered **0 through 27**.
   - All blocks except the last use size 1024 (SZX=6).
   - Last block (frame 54) is block #27, `M=0`, payload 501 bytes.
   - Total reconstructed payload: **28 149 bytes**.
   - Block2 option values are 1-byte for block numbers 0-15 (`d10aNN`) and
     2-byte for block numbers 16-27 (`d20a0NNN`).

The capture ordering is anomalous: several tracker responses appear before the
matching phone requests. Matching must be done by token (and MID), not by frame
order. Some request frames are encrypted under MobileData keys and are not
visible in the bootstrap-decrypted set; the offline PoC detects and reports the
resulting gaps if data were missing. In this capture the bootstrap-decrypted
`/sync/dump` blocks are contiguous and complete.

### 1.4 Roles of other endpoints

| Endpoint | Role in bootstrap |
|----------|-------------------|
| `/pair/display` | Phone PUTs display-pairing data. Two requests in the capture; both return `2.04 Changed`. |
| `/events` | Tracker POSTs/subscribes to events. Phone returns `2.04 Changed`. |
| `/md/3d02` | Tracker PUSHes MobileData metadata (`md/3d02`). Phone returns `4.01 Unauthorized` twice, suggesting the bootstrap identity is not authorized for this metadata channel. |
| `/sync/response` | Phone PUTs final response at the end of the bootstrap session (frames 203-204). Tracker replies `2.31 Continue` and `2.04 Changed`. |

### 1.5 Observed values vs assumptions

**Observed:**
- CoAP version 1, TKL=4, token-based request/response pairing.
- URI paths, query strings, Content-Format option (6 = application/octet-stream),
  Block2 option (23).
- Block sizes of 1024 bytes (SZX=6).
- Response messages omit URI-Path options.

**Assumptions / inferences:**
- The reason `/sync/config` returns 4.01 is inferred from the ordering of
  operations, not from a human-readable error payload.
- `/md/3d02` payloads are binary and their exact semantics are not parsed.
- The MobileData identity `MD-A376B03C-6AF89570` is observed but its key is not.

## 2. Offline PoC design and test results

The offline PoC lives in `prototype_analysis/offline_poc/` and works entirely
on the captured data. It does not contact the tracker.

### 2.1 Components

| File | Purpose |
|------|---------|
| `coap_parser.py` | RFC 7252 CoAP parser: version, type, code, token, MID, options (URI-Path, URI-Query, Content-Format, Block1/Block2), payload. Supports extended option deltas. |
| `blockwise_reassembler.py` | Reassembles block-wise transfers by token or by inferred URI-Path. Detects missing blocks and duplicate blocks. |
| `sync_dump_reconstructor.py` | Builds a token->path map from requests, labels response blocks, splits `/sync/dump` transfers on block #0, and reports completeness. |
| `test_offline_poc.py` | Unit tests using captured examples and the full CSV/bin sample set. |

### 2.2 Key design choices

- **Responses have no URI-Path.** The PoC therefore infers each response's
  endpoint from the matching request token before reassembly.
- **Block #0 starts a new transfer.** This separates the small
  `/sync/dump?t=pair` response from the main `/sync/dump` transfer.
- **Completeness is verified.** A transfer is complete only when the highest
  block has `M=0` and all lower block numbers are present.

### 2.3 Test results

```
$ python test_offline_poc.py
Ran 10 tests in 0.021s
OK
```

Notable tests:
- Parser validates frame 9 (`PUT /pair/display`), frame 12/14 (`GET /sync/config` / `4.01`),
  frame 23 (`2.05` Block2 #0 size 1024), and large block numbers (frame 41, #16).
- Reassembler reconstructs the main `/sync/dump` transfer as blocks 0..27,
  28 149 bytes, with no missing blocks.
- Every row in `decrypted_coap_samples.csv` parses as valid CoAP.

## 3. Live bootstrap implementation plan

The plan is documented in full in `prototype_analysis/LIVE_BOOTSTRAP_PLAN.md`.
Summary:

1. **BLE:** Connect to the GoldenGate service (`abbaff00...`), write on
   characteristic `0x0042`, enable notifications on `0x0044`.
2. **Framing:** Use the 1-byte ATT prefix and IPv4 total-length slicing
   (handled natively by `libxp.so` via `RxSource`/`TxSink`).
3. **DTLS:** Bootstrap with `TLS_PSK_WITH_AES_128_CCM`, identity `"BOOTSTRAP"`,
   using the existing `BootstrapTlsKeyResolver` PSK.
4. **CoAP:** Send `GET /.well-known/core` or `GET /sync/config` through
   `CoapEndpoint` + `OutgoingRequestBuilder` + `SingleCoapResponseListener`.
5. **Block transfer:** Extend the probe to follow `Block2` `M` bits and request
   successive `/sync/dump` blocks.

### 3.1 Existing BlueBit components to reuse

From `BLUEBIT_CODE_INVENTORY.md`:

| Component | Reuse for |
|-----------|-----------|
| `AndroidBleConnection.kt` | BLE connect/discover/write/notify |
| `FitbitGattlinkConnector.kt` / `FitbitGattlinkTransport.kt` | BLE byte transport |
| `GoldenGate.kt`, `RunLoop.kt`, `Bridge.kt`, `RxSource.kt`, `TxSink.kt` | Native stack lifecycle |
| `Stack.kt`, `DtlsSocketNetifGattlinkStackConfig.kt` | IPv4/UDP/DTLS/Gattlink stack |
| `BootstrapTlsKeyResolver.kt`, `TlsKeyResolverRegistry.kt` | PSK resolution |
| `DtlsProbeStateMachine.kt` | Detect DTLS state 2 = established |
| `CoapEndpoint.kt`, `OutgoingRequestBuilder.kt`, `SingleCoapResponseListener.kt`, `OptionsBuilder.kt` | CoAP request/response |
| `CoapReadOnlyProbeLogic.kt`, `CoapReadOnlyProbe.kt` | End-to-end read-only probe pattern |

### 3.2 New code needed

A thin Kotlin driver under `prototype_analysis/live_bootstrap/` that wires the
above components and exposes a single "run bootstrap + send one CoAP request"
entry point. No new protocol logic is required.

## 4. MobileData boundary

The bootstrap session ends when the tracker initiates a new DTLS handshake with
a MobileData PSK identity (`MD-A376B03C-6AF89570` in the capture). After that,
tracker->phone traffic is encrypted under MobileData keys and cannot be
decrypted with the bootstrap PSK.

- **In scope for the first live test:** Everything up to and including the
  bootstrap `/sync/response` final PUT.
- **Out of scope:** MobileData handshake, post-bootstrap `/events`, and any
  further `/sync/dump` blocks under MobileData keys.

Evidence still needed for MobileData:
- The 16-byte PSK for the observed `MD-...` identity.
- Whether that key is derived from a per-device seed or provisioned out-of-band.

## 5. What can be reproduced offline

Everything in the bootstrap-decrypted portion of the capture:
- Parse all 74 CoAP messages and validate headers/options.
- Reconstruct `/sync/dump` block-wise transfers and verify contiguous blocks.
- Verify DTLS key derivation (master secret, write keys/IVs) against the
  bootstrap PSK.
- Confirm the bootstrap PSK from `BootstrapTlsKeyResolver.kt` decrypts 74
  application-data records with valid AES-CCM tags.

## 6. What remains necessary for a live connection

- A paired Inspire 3 within BLE range.
- Android BLE permissions and bonding already established.
- The native `libxp.so` GoldenGate library loaded and run-loop started.
- Correct sequencing of `Bridge`, `Stack`, and DTLS state listener.
- Tolerance for the tracker's initial `MD-...` handshake attempt and fallback
  to `BOOTSTRAP`.

## 7. The next smallest test that demonstrates real progress

**Goal:** Establish the DTLS bootstrap session and receive a valid CoAP
response to `GET /.well-known/core` from a live tracker.

**Why this is the right next step:**
- It exercises the full BLE -> Gattlink -> IPv4/UDP -> DTLS -> CoAP stack.
- It reuses the largest existing components (`DtlsBootstrapProbe`,
  `CoapReadOnlyProbe`) with minimal new code.
- It does not require MobileData keys or a complete sync.
- A pass/fail signal is unambiguous: a decrypted 2.xx/4.xx CoAP response proves
  end-to-end encryption and application-layer reachability.

## 8. Deliverables

All files are under `G:/Dev/BlueBit/prototype_analysis/`:

- `PROTOCOL_PROTOTYPE_REPORT.md` (this document)
- `LIVE_BOOTSTRAP_PLAN.md`
- `BLUEBIT_CODE_INVENTORY.md` (produced by parallel worker)
- `offline_poc/coap_parser.py`
- `offline_poc/blockwise_reassembler.py`
- `offline_poc/sync_dump_reconstructor.py`
- `offline_poc/test_offline_poc.py`
- `offline_poc/README.md`

## 9. Constraints respected

- No contact with the tracker was made.
- No pairing, firmware, or device settings were changed.
- Existing captures and source code were preserved.
- Captured data and secrets were not sent to external services.
