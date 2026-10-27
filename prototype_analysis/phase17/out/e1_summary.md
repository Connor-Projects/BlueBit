# Phase 17 — E1/E2 offline results

- Input: `inspire3_decryption_analysis\decrypted_coap_samples.csv`
- Input SHA-256: `4dc8c8a16a098c99325b09e0cbfcceaf03157de186601956133b4d12eab72d33`
- Decrypted CoAP messages: **74**
- Inner-frame range: 9..204
- DTLS identity for the whole set: **BOOTSTRAP** (bootstrap session)

## E1 — resource x method x status (requester -> responder)

| resource | requester | responder | methods | statuses | n |
|---|---|---|---|---|---|
| `/events` | tracker | phone | 0.02 POST | 2.04 Changed | 1 |
| `/md/3d02` | tracker | phone | 0.02 POST | 4.01 Unauthorized | 2 |
| `/pair/display` | phone | tracker | 0.03 PUT | 2.04 Changed | 2 |
| `/sync/config` | phone | tracker | 0.01 GET | 4.01 Unauthorized | 1 |
| `/sync/dump` | phone | tracker | 0.01 GET | 2.05 Content | 28 |
| `/sync/dump?t=pair` | phone | tracker | 0.01 GET | 2.05 Content | 1 |
| `/sync/response` | phone | tracker | 0.03 PUT | 2.04 Changed|2.31 Continue | 2 |

## E2 — `/bond/control` presence

**No `bond/control` message exists in the captured official session.** (0 bond-like messages; 0 control-like messages.)

DTLS identities on the wire: MD-A2CB0B8E-6AE5D947, BOOTSTRAP, MD-A376B03C-6AF89570

## Interpretation

- Under the **BOOTSTRAP** session, the tracker (as CoAP server) **allowed** `/pair/display` (2.04), `/sync/dump` (2.05), `/sync/response` (2.31/2.04), and **denied** `/sync/config` with **4.01 Unauthorized**.
- The tracker's own requests to `/md/3d02` were answered **4.01 by the phone** (the official app refusing MobileData metadata at that point) — a different actor.
- The capture is a **post-provisioning sync**: the tracker is already provisioned (it presents a MobileData identity and re-keys to MobileData), so the official app has no reason to send `bond/control`. The capture therefore constrains but does not directly answer what `bond/control` requires.

