# Live Bootstrap Implementation Plan

**Scope:** This document is the design for the smallest live BlueBit
implementation that can reproduce the captured Fitbit Inspire 3 bootstrap
session. It does not implement the MobileData session; that boundary is
explicitly documented in §7.

**Sources:** Based on the decrypted Inspire 3 capture and the BlueBit code
inventory (`prototype_analysis/BLUEBIT_CODE_INVENTORY.md`).

## 1. Goal of the first live test

Establish a DTLS bootstrap session with a paired Inspire 3 and send one
successful CoAP request (default `GET /.well-known/core`, optionally
`GET /sync/config`). The test succeeds when the tracker returns a valid CoAP
response over the encrypted DTLS channel.

No actual sync data needs to be fetched; the objective is to prove the live
stack end-to-end.

## 2. BLE connection

### 2.1 Target GATT service and characteristics

From `FitbitGattUuids.kt` and the capture:

| Attribute | UUID / Handle | Role |
|-----------|---------------|------|
| Golden Gate service | `abbaff00-e40a-4eff-8783-4ffb2d79c3d9` | Primary service |
| Phone -> tracker (write) | handle `0x0042` (characteristic `abbaff01...`) | ATT Write Command carrying IPv4/UDP/DTLS to tracker |
| Tracker -> phone (notify) | handle `0x0044` (characteristic `abbaff02...`) | ATT Notification carrying IPv4/UDP/DTLS from tracker |

### 2.2 Reusable BlueBit components

- `bluebit/core/bluetooth/android/AndroidBleConnection.kt` — raw `BluetoothGattCallback` connect/discover/write/notify.
- `bluebit/fitbit/transport/FitbitGattlinkConnector.kt` — bridges BLE writes/notifications into a byte transport.
- `bluebit/fitbit/transport/FitbitGattlinkServiceSelector.kt` — selects the GoldenGate service/characteristics.

### 2.3 New / adapted code

A small Kotlin driver (e.g. `prototype_analysis/live_bootstrap/BootstrapLiveDriver.kt`) that:
1. Scans for the paired device by BLE address or name.
2. Connects, discovers services, enables notifications on `0x0044`.
3. Writes to `0x0042` and receives notifications via `FitbitGattlinkConnector`.

## 3. ATT framing and IPv4/UDP reconstruction

### 3.1 Observed framing

Each ATT value is prefixed by a **1-byte control/sequence byte**:

| Prefix range | Meaning |
|--------------|---------|
| `0x00-0x7F` | Data PDU (payload may be empty = ACK) |
| `0x80` | Empty link-control event |
| `0x81` | MTU advertisement (`00000808` = 2056 bytes, big-endian) |

Data PDUs are concatenated per direction and sliced into IPv4 packets using
the 16-bit IPv4 total-length field. Zero-length data PDUs are ACKs and do not
advance the cursor.

### 3.2 Reusable BlueBit components

- `bluebit/fitbit/transport/FitbitGattlinkTransport.kt` / `FitbitGattlinkConnector.kt` — already abstract a bidirectional byte pipe over BLE.
- The native `libxp.so` GoldenGate stack (`GoldenGate.kt`, `Stack.kt`) implements this framing internally when fed by `RxSource`/`TxSink`.

### 3.3 New / adapted code

If we bypass the native stack for the first experiment, reuse the Python logic
from `analyse_inspire3_capture.py` (ATT TSV -> IPv4 slicing) and port it to a
minimal Kotlin helper. However, the preferred path is to let the native stack
handle Gattlink framing (see §4).

Endpoints observed in the capture:

| Role | IPv4 | UDP port |
|------|------|----------|
| Phone / BlueBit | `169.254.0.2` | `5684` |
| Tracker | `169.254.0.3` | `5684` |

## 4. Native GoldenGate stack

### 4.1 Reusable BlueBit components

| File | Purpose |
|------|---------|
| `GoldenGate.kt` | Loads `libxp.so` and checks readiness. |
| `RunLoop.kt` | Starts the native run-loop thread required by the stack. |
| `Bridge.kt` + `RxSource.kt` + `TxSink.kt` | Join inbound BLE bytes to native and push outbound bytes to BLE. |
| `Stack.kt` | Creates the native IPv4/UDP/DTLS/CoAP stack from a `NodeKey`, bridge, and config. |
| `DtlsSocketNetifGattlinkStackConfig.kt` | `DSNG` config combining DTLS socket network interface + Gattlink. |
| `GattlinkStackConfig.kt` | Pure-Gattlink descriptor (`G`) config. |

### 4.2 Configuration

Use `DtlsSocketNetifGattlinkStackConfig` with the observed link-local endpoints:
- Local: `169.254.0.2:5684`
- Remote: `169.254.0.3:5684`

The native stack will act as the DTLS server (phone side) and run the CoAP
endpoint.

### 4.3 New / adapted code

A `LiveBootstrapStack` wrapper that:
1. Calls `GoldenGate.init(context)`.
2. Starts `RunLoop`.
3. Builds a `Bridge` with the active `FitbitGattlinkConnector`.
4. Creates `Stack` with `BluetoothAddressNodeKey(device.address)` and
   `DtlsSocketNetifGattlinkStackConfig`.
5. Registers a DTLS state listener and waits for `SESSION_ESTABLISHED` (state 2).

## 5. DTLS 1.2 bootstrap

### 5.1 Cipher suite and PSK

- DTLS version: **1.2** (`0xfefd`).
- Cipher suite: **TLS_PSK_WITH_AES_128_CCM** (`0xC0A4`).
- Bootstrap identity: ASCII `"BOOTSTRAP"`.
- Bootstrap key: 16-byte literal from `BootstrapTlsKeyResolver.kt`.

### 5.2 Reusable BlueBit components

| File | Purpose |
|------|---------|
| `BootstrapTlsKeyResolver.kt` | Returns the bootstrap PSK when key ID == `"BOOTSTRAP"`. |
| `HelloTlsKeyResolver.kt` | Returns the `hello` PSK for the initial handshake attempt. |
| `TlsKeyResolverRegistry.kt` | Chains resolvers (hello → bootstrap). |
| `DtlsProbeStateMachine.kt` | Interprets DTLS state callbacks, treats state 2 as established. |

### 5.3 New / adapted code

Register the existing resolvers in the `TlsKeyResolverRegistry` and attach a
`DtlsProbeStateMachine` (or simpler listener) to the stack. No new key material
is needed; the PSK is already in the repository.

### 5.4 Handshake flow

1. Tracker sends `ClientHello` with cipher suite `TLS_PSK_WITH_AES_128_CCM`.
2. Phone (native stack) sends `ServerHello` + `ServerHelloDone`.
3. Native stack calls the resolver for the PSK identity; registry returns the
   `BOOTSTRAP` key.
4. Tracker sends `ClientKeyExchange`, `ChangeCipherSpec`, `Finished`.
5. Phone sends `ChangeCipherSpec`, `Finished`.
6. Bootstrap epoch 1 established.

The capture shows the tracker first tries a per-device MobileData identity
(`MD-...`) which is rejected with `unknown_psk_identity`; the tracker then
retries with `BOOTSTRAP`. The native stack must therefore reject unknown PSK
identities and allow the tracker to fall back.

## 6. Sending and receiving CoAP

### 6.1 Reusable BlueBit components

| File | Purpose |
|------|---------|
| `CoapEndpoint.kt` | Native CoAP endpoint wrapper. |
| `OutgoingRequestBuilder.kt` | Fluent builder for CoAP requests. |
| `SingleCoapResponseListener.kt` | One-shot listener returning `Result<IncomingResponse>`. |
| `OptionsBuilder.kt` | Adds URI path, query, content format, block options, etc. |
| `CoapReadOnlyProbeLogic.kt` | Shared logic: wait for DTLS, send CoAP GET, parse response. |
| `CoapReadOnlyProbe.kt` | Android diagnostic that wires the above together. |

### 6.2 Smallest first request

Reuse `CoapReadOnlyProbeLogic` with path `/.well-known/core` (or `/sync/config`
to reproduce the capture exactly). The logic:

1. Wait for DTLS state == `SESSION_ESTABLISHED`.
2. Build request with `OutgoingRequestBuilder(Method.GET, path)`.
3. Attach `SingleCoapResponseListener`.
4. Call `CoapEndpoint.sendRequest(request, listener)`.
5. Return `CoapReadOnlyResult(success, dtlsEstablished, coapSuccess, responseCode, ...)`.

### 6.3 Block-wise transfer

To fetch `/sync/dump`, extend the probe logic to handle `Block2` responses:
- If a response has `Block2` with `M=1`, send a new `GET /sync/dump` with
  `Block2` option set to the next block number (use `OptionsBuilder` block
  support).
- Repeat until `M=0`.
- Reassemble payloads in order; detect missing block numbers.

The offline PoC in `prototype_analysis/offline_poc/` already implements the
reassembly algorithm and can be referenced when writing the live Kotlin logic.

### 6.4 Retransmissions

CoAP retransmissions are handled by the native CoAP endpoint (libxp). At the
Kotlin layer, implement a timeout around `SingleCoapResponseListener` and retry
the whole request if it completes with an error. Match requests/responses by
token, not by MID, because the capture shows out-of-order MIDs.

## 7. MobileData boundary

The bootstrap session ends and the MobileData session begins when the tracker
initiates a new DTLS handshake with a `MD-XXXXXXXX-XXXXXXXX` PSK identity. In
the capture this happens after the last bootstrap-decrypted frame (frame 204,
`/sync/response`).

### 7.1 Bootstrap scope (initial prototype)

- BLE connect + Gattlink framing.
- DTLS bootstrap with `BOOTSTRAP` PSK.
- One successful CoAP request/response, optionally `/sync/dump` block-wise
  transfer under the bootstrap keys.
- `/sync/response` final PUT at the end of bootstrap.

### 7.2 MobileData scope (explicitly out of scope for initial prototype)

- Second DTLS handshake with identity `MD-A376B03C-6AF89570`.
- All application-data records after the MobileData handshake.
- Continued `/sync/dump` blocks or `/events` traffic encrypted under MobileData
  keys.

### 7.3 Evidence needed to extend into MobileData

1. The 16-byte PSK that corresponds to the observed `MD-...` identity. The
   capture contains only the identity, not the key.
2. Key derivation: whether MobileData keys are derived from a per-device seed
   (serial, BLE address, pairing token) or provisioned out-of-band.
3. Source code or firmware artifact containing the `MD-A376B03C-6AF89570` key
   or the KDF that produces it.

Until that evidence is found, the live prototype must stop at the bootstrap
boundary and report success/failure there.

## 8. Suggested code layout

```
prototype_analysis/
├── live_bootstrap/
│   ├── BootstrapLiveDriver.kt       # BLE connect + GattlinkConnector setup
│   ├── LiveBootstrapStack.kt        # GoldenGate/RunLoop/Stack wrapper
│   └── LiveBootstrapActivity.kt     # Minimal Android entry point
└── offline_poc/                     # existing offline parser/reassembler
```

## 9. Testing sequence

1. **Unit / offline:** Confirm the offline PoC still passes (`python test_offline_poc.py`).
2. **BLE only:** Confirm notifications arrive on `0x0044` when writing the MTU
   advertisement (`0x81 00000808`) to `0x0042`.
3. **DTLS handshake:** Confirm `ClientHello` is received and the native stack
   sends `ServerHello`.
4. **CoAP ping:** Send `GET /.well-known/core` and receive a 2.05 or other
   valid CoAP response.
5. **Block transfer:** Optionally fetch `/sync/dump` and verify block numbers
   are contiguous using the offline reassembly logic.

## 10. Risks and open questions

- The native `libxp.so` may require exact ordering of `RunLoop`, `Bridge`, and
  `Stack` creation; refer to `DtlsBootstrapProbe.kt` for the working sequence.
- Android BLE bonding/pairing must already be in place; this plan does not
  perform new pairing.
- Some `/sync/dump` request/response pairs in the capture are encrypted under
  MobileData keys, so a live bootstrap `/sync/dump` fetch may differ from the
  offline reconstruction.
- The tracker may reject the first `MD-...` handshake attempt and fall back to
  `BOOTSTRAP`; the stack must tolerate this.
