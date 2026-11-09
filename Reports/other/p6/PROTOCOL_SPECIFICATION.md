# FitCloud P6 — Wire Protocol Specification (BlueBit native stack)

**Status:** Draft v1 — static-analysis derived, hardware validation pending.
**Date:** 2026-10-30
**Sources:** decompiled FitCloudPro `sms_fitcloud_pro_209` APK (`research/fitcloud-apk/decompiled/jadx/sources/**`) + physical P6 evidence (`Reports/FitCloud_P6_Validation_Report.md`, `Reports/FitCloud_P6_GATT_Discovery.md`, `Reports/p6_gatt_dump_*.txt`).
**Purpose:** Implement an independent (non-SDK) FitCloud P6 stack in BlueBit. Every claim is marked **[V] verified** (direct code/hardware evidence) or **[A] assumed** (consistent inference; must be confirmed on hardware before relying on it). Nothing here may be used as a test expectation unless it is [V] with an evidence pointer, or [A] explicitly marked test-eligible.

Corrections to earlier reports are listed in §12.

---

## 1. BLE layer

| Item | Value | Status | Evidence |
|---|---|---|---|
| Transport service | `000001ff-3c17-d293-8e48-14fe2e4da212` | [V] | `p150kd/j.java:155` + P6 GATT dump service [2] (`Reports/FitCloud_P6_GATT_Discovery.md`) |
| Write characteristic | `0000ff02-0000-1000-8000-00805f9b34fb` (WRITE) | [V] | `p150kd/j.java:156` + GATT dump |
| Notify characteristic | `0000ff03-0000-1000-8000-00805f9b34fb` (READ, WRITE_NO_RESPONSE, NOTIFY) | [V] | `p150kd/j.java:157` + GATT dump |
| Enable notifications | write CCCD `00002902` on `ff03` with enable-notify value | [V] for the requirement | exact CCCD bytes not observable statically; standard enable-notify value `0x0100` assumed — confirm in first capture [A] |
| Advertised service `0x3802` | Not in connected GATT (advertisement-only) | [V] | P6 GATT dump; do **not** wait for it after connect |
| Write type on `ff02` | WRITE (with response) | [V] | GATT dump property = WRITE only |
| MTU [A] | Android negotiates larger MTU; transport maxLen parameter is the negotiated MTU | [A] | `p363zd/e.java:89–96` ctor param; value not observable statically. Physical logs `GATT_MTU_CHANGED mtu=247` expected on first session — verify |
| Write split [V] | Packets longer than MTU are split by the BLE write API into MTU-sized writes | [V] | `p363zd/e.java:204–211` (`nVarA.c(mtu)`) |

Other discovered services (`1800`, `ff12`, `0000d0ff-…`, `00006287-…`) are not used by the basic stack. CCCDs also exist on `ff14` (svc `ff12`) and `6487` (svc `6287`) — not subscribed by the transport connector (`p150kd/j.java` connects only `ff03` of `000001ff-…`).

## 2. Transport framing (`Fc#TransportPacket`, class `U1.e`)

All multibyte header fields are **big-endian**. Header is exactly 8 bytes:

```
[0]    magic 0xAA
[1]    flags
[2..3] payloadLength  BE16  (bytes of encrypted/plain payload that follow the header)
[4..5] crc           BE16  (CRC-16 over PLAINTEXT payload; 0x0000 when payloadLength==0)
[6..7] sequenceId    BE16  (packet sequence number, "SN")
```
[V] `U1/e.java:79` (encode), `U1/e.java:159–171` (decode), magic from `StatusIndex.ANC_MODE == 0xAA`.

### 2.1 Flags byte [1]

| Bit | Meaning | Status | Evidence |
|---|---|---|---|
| 0x10 | This 8-byte frame is a transport ACK (header-only; payloadLength must be 0) | [V] | `U1/e.java:53,79,166–173` |
| 0x20 | ACK **failure** indicator (set together with 0x10) — receiver verified the packet **successfully only when 0x20 is clear** | [V] | `U1/e.java:54,79`; receive loop `i9/b.java:268–337` — app acks a *valid* packet with flags 0x10 (`a(false, sn)`), a *CRC-failed* packet with 0x30 (`a(true, sn)`), and a *reassembly timeout* with 0x30 (`i9/b.java:278`); outbound acks from device with 0x20 set keep the sender retrying (`f54932m = !success`, line 331) |
| 0x40 | Payload is AES-encrypted | [V] | `U1/e.java:79,167` |

**Correction to earlier REPORT v2:** "0x20 = ack success" is wrong. 0x20 set = *failure* (see §12).

ACK frames: 8 bytes header only, `payloadLength = crc = 0`. Interpretation of an ACK byte 3 (`p349yd.b`): `fail = (flags & 0x20) != 0`. [V]

### 2.2 CRC-16

- Algorithm: byte-wise table CRC, init `0x0000`, no final XOR:
  `crc = (crc >> 8) ^ table256[(crc ^ b) & 0xFF]` for each plaintext payload byte.
- Table constant: `p339xe/j.java:35` `f54196a` — equals the standard reflected table for polynomial **0x8005** (reflected 0xA001), i.e. CRC-16/ARC family: table[1]=0xC0C1, table[2]=0xC181, table[3]=0x0140, table[4]=0xC301, table[5]=0x03C0, table[7]=0xC241. [V]
- The same routine is used on TX (over the **plaintext** payload, before encryption) and on RX (over the **decrypted** payload); a mismatch ⇒ ACK with flags 0x30 and the sender retransmits. [V] `U1/e.java:69–70,125–132`
- CRC field and `payloadLength` are both 0x0000 for ACK frames. [V] (`U1/e.java:53`)

> The earlier findings.json classed this as "CRC-16/IBM (poly 0xA001)". Same polynomial; the *initial value 0 and no-reflection-after* detail is what makes it checked consistent here. Published CRC-16/ARC check value `0xBB3D` for `"123456789"` is **not** in the evidence bundle; use it only as a sanity test in [A]-mode.

### 2.3 Payload = encrypted flag mirroring

- Received header sets the global `encrypted` mirror (`U1/e.java:167`, transmitted packet encrypted status is mirrored from the *previous received* frame's 0x40 bit). [V]
- Auth requests (`payload[0]==3 && payload[2]∈{0x11,0x13}`) are **always sent plaintext**: `U1/e.java:76–78` clears the encrypted flag for those packets. [V]
- After successful auth the device replies encrypted, so all further frames in both directions carry 0x40/payload AES. [V] code path; observed on hardware (doLoginAuth result:0 then encrypted config push) [A — hardware logs do not show hex, per-device confirmation pending]

### 2.4 Notification reassembly + heartbeat

- `ff03` notifications feed `U1.e.e()` parse; a multi-notification packet reassembles until `payloadLength` bytes accumulate. Reassembly cap 1024 B. [V] `U1/e.java:112–115`
- While reassembly is in progress (`f12361a==true`) the send path **waits** (500 ms poll loop). [V] `p363zd/e.java:220–237`
- Reassembly starvation: if no frame arrives for 10 s while incomplete, sender sends ACK flags 0x30 and restarts parsing. [V] `i9/b.java:268–283`
- A duplicate (`sequenceId` == last delivered) is dropped but **re-acked**. [V] `i9/b.java:308–322`

## 3. AES-256 "CTR" keystream (`p334x9.b.o()`)

Verified implementation (`p334x9/b.java:476–516`):

```
IV' = session IV; IV'[14..15] = sequenceId big-endian
keystream = AES-256-ECB-encrypt(sessionKey, IV')      // exactly ONE 16-byte block
out[i]    = in[i] ^ keystream[i % 16]                 // X-or keystream cyclically
```

- **This is not true CTR**: the counter never increments; the 16-byte keystream repeats across the whole payload. [V]
- The transformation is its own inverse (`o()` used for both encryption and decryption). [V]
- Key = 32 bytes, IV = 16 bytes, both session-scoped (§5). `sequenceId` = the frame's own [2..7] field (RX decrypt uses the *received* header SN; TX encrypt uses the *outgoing* SN). [V] `U1/e.java:87,101,141`

### 3.1 Session key/IV derivation (`p150kd/j.java:382–424`)

Inputs: `userId` string (UTF-8 — `p110hg/a.java:26`), `mac` string `"90:EB:99:D6:5F:99"` parsed as 6 bytes, constants below.

```
uidBlock  = UTF8(userId) prefix-truncated to 28 bytes, zero-padded to 32
constBlock= repeat({0x4D,0x4A,0x42,0x54,0x55,0xAA,0xA5,0x5A}, 4)   // 32 bytes
key[32]   : key[i] = (i<6 ? mac[i] : 0x00) ^ uidBlock[i] ^ constBlock[i]
iv[16]    : {0xD4, 0xEF, 0xF0, 0xD3, 0xF4, 0xE5, 0xF0,
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5],
             0xA1, 0x00, 0x00}
```
[V — exact bytes visible at `p150kd/j.java:394–424` (77,74,66,… = 0x4D,0x4A,0x42…) and IV values `bArr5` block 399–417.]

Used for **both** BIND and LOGIN sessions. Cleared on disconnect: `p150kd/j.java:256–257` sets key and IV to `null` ⇒ every fresh GATT connection restarts with a fresh session and SN. [V] Key derivation is platform-independent — nothing else is mixed in (no userId in IV, no MAC length dependence beyond the parse).

## 4. On-the-wire protocol packet (`FcProtocolPacket`, `p349yd.a`)

A packet describes `(cmdId: u8, keyId: u8, keyData: bytes?)`. On the wire, inside the transport payload that precedes CRC/encryption it is serialized by `.ping/a pr senders` — concretely `p363zd/e.java:163–182` [V]:

```
TX (one item):
payload = [ cmdId ][ 0x00 ][ keyId ][ lenHi ][ lenLo ][ keyData... ]   // len = keyData.length BE16
TX (no data):
payload = [ cmdId ][ 0x00 ][ keyId ][ 0x00 ][ 0x00 ]
```

RX container parse (`Hd/C0501l.java:21–53`) [V]:

```
RX payload = [ cfgId ][ reserved ][ then repeated items ]        // byte[1] ignored on RX
item     = [ keyId ][ lenHi ][ lenLo ][ value(len) ]             // len BE16
```

RX container parse (`Hd/C0501l.java:21–53`) [V]:

```
RX payload = [ cfgId ][ reserved ][ then repeated items ]        // byte[1] read but unused by the parser
item     = [ keyId ][ lenHi ][ lenLo ][ value(len) ]             // len BE16
```

=> The wire format is symmetric: `[cmdId][0x00]` then one or more `[keyId][lenBE16][value]` items. On TX, byte[1] is always `0x00`.

Observed `(cfgId, keyId)` usage:
- `2` = config pushes. The server consumed pushes `cfgId=2, keyId=0x21` whose value is itself an inner TLV container `[key:u8][len:u8][value…]` (§8). Other config sub-keys (`0x24`, `0x30`, `0x20`, `0x17`, `0x1A`, `0x27`, `0x2A`, `0x2D`) referenced by the `Fc#ConfigFeature` cache (`config2_<n>` `Hd/q.java:87–171`). Exact set of sub-key semantics beyond (`0x21`→deviceInfo) is not mapped — out of MVP scope. [V for structure, A for sub-key semantics]
- `3` = auth family (§7).
- `5` = settings/dial family (`p026be/e.java` accept-list: `(5,65)` push of dial-space updates etc.). Out of MVP scope. [V structure]
- `8` = voice/aux family (`Fd/g.java:92` sends `(8, …)`), out of MVP scope. [V existence]

## 5. Sequence numbers, ACK protocol, retransmit [V]

Evidence: `i9/b.java` (RX loop, tags `Fc#ProtocolQueue`) and `p363zd/e.java` (send, `g()`).

### 5.1 Outgoing data packets (app → device)

1. SN source: `AtomicInteger(1)` at connection start ⇒ first data packet SN = 1. [V] `p363zd/e.java:104`
2. Build frame with CRC + SN; encrypted by session state (§2.3).
3. Write to `ff02` (mtu-split), then wait ≤500 ms (retry loop): the send completes on receiving an ACK whose SN == current expected (`f54934o.get() == ackSN`) **and** 0x20 clear; else retry (same SN, same bytes) up to 3 attempts total. [V] lines 245–266 + `i9/b.java:326–337`
4. SN increments **after** the ack/drop-decision path finishes (per logical packet, not per write attempt). [V] line 266
5. While a multi-frame RX packet is being reassembled, sending is paused (§2.4).

### 5.2 Incoming data packets (device → app)

1. On a fully reassembled, CRC-valid, non-header-only frame: decrypt (if 0x40), verify CRC over decrypted bytes, parse §4 items, emit each `(cfgId,keyId,data)`. [V]
2. Deduplication: same SN ⇒ re-delivery suppressed; still ACKed with 0x10. [V]
3. **Every valid data frame is ACKed** with an 8-byte ACK, SN = received SN, flags 0x10. [V]
4. CRC failure (or reassembly timeout) → ACK flags 0x30 (NAK) with same SN; peer should retransmit. [V]

### 5.3 Incoming ACKs (device → app)

- ACK must be 8 bytes, flags include 0x10, `[2..5] == 0` [V]; `p349yd.b`(fail:flags&0x20, sn).
- When not expecting an ACK → logged error only. [V]
- Mismatched SN → logged "sequenceId expect %d but %d", and current flow stalls; after the 500 ms deadline the frame is re-sent. [V]

## 6. Session lifecycle

| Phase | Behaviour | Status |
|---|---|---|
| Connect | raw GATT connect (no LE pairing needed — P6 works with `BOND_NONE`) | [V] hardware: Phase 2 of `Validation_Report` |
| Discover | discover services; assert `000001ff-…` present with `ff02`+`ff03` | [V] |
| Subscribe | enable notifications on `ff03` (write CCCD enable-notify) | [V] |
| Key | derive key/IV from userId + MAC (§3.1) | [V] |
| Auth | BIND xor LOGIN (§7) | [V] sequencing, [A] response id/payload details |
| Data | config pushes arrive; sends acked; any packets are protocol packets; peers exchange `0x40`-flagged frames after auth | [V/A mixed] |
| Disconnect | BLE disconnect; key/IV nulled next session (SN restarts at 1). Token kept in storage (§7.3) | [V] |

No keep-alive/ping is mandated by the observed send loop; the 10 s RX reassembly timeout is the only static heartbeat in evidence. [V]

## 7. Authentication (`p026be/f.java` + `Fd/g.java:222–248` + `Id/h.java:30–66`)

### 7.1 User-id block

`p334x9.b.k(userId, isBind)`: `data = 32` bytes, first `min(UTF8(userId).length, 28)` bytes = userId bytes, rest zero. Wire: `a(3, isBind?0x11:0x13, data)`. [V]

### 7.2 Sequences [V — sequencing]

- **BIND** (`doBindAuth`): send `a(3, 0x12, null)` → device replies (result codes `data[0]`: `0`=OK, `1`,`2`,`3`,`5`→error, else −1 (`Fd/g.java:222–248`)). On `0`: generate session token (§7.3) and send `a(3, 0x11, uidBlock)`. (~B complete; `p026be/a.java` then drops further error handling — the (3,0x11) response is not observed dependency-free in this trace.) [A: response handling of (3,0x11) not statically resolved]
- **LOGIN** (`doLoginAuth`): send `a(3, 0x14, null)` → response `data[0]`: `0`=OK (uses stored token2; creates if absent), `1`= **"bind required"** → with `firstAuth` unset, aborts (`p026be.b` → outer layer falls back to BIND); other values → auth error `.a(−1)`. On `0`: send `a(3, 0x13, uidBlock)`. `p026be/h.f18018e`-flag tracks "firstAuth" state. [V]
- App behaviour: first-ever connection with a userId ⇒ BIND; on subsequent connects with same userId ⇒ LOGIN only (`p026be/f.java:51–83`: if last successful mode was neither BIND-with-firstAuth nor LOGIN, choose LOGIN). Verified userId change ⇒ clear `syncTimes<N>` and `epo2_` and start over in BIND-eligible state; token per userId derived from `f18016c`. [V]
- **No key/mix from token to crypto** — token is purely bookkeeping; the AES key derives only from userId+MAC. [V]

### 7.3 Token bookkeeping [V]

`token2_<MAC-without-colons>` (e.g. `token2_90EB99D65F99`) in SP `fc_internal_storage`, value = `Long.toString(now_ms, 36)` (e.g. `mu5z25th`). Rewritten on every successful BIND; read (not rewritten) on successful LOGIN.

### 7.4 Which responses to await [A — flagged]

The exact `(cmdId,keyId)` of the bind/login result push is not pinned statically (`Fd/g.java:222–248` receives "first packet after send" — no keyId filter in evidence). Hardware logs show `doLoginAuth result:0` logged ⇒ data-carrying reply exists. **Test plan:** capture in logcat `Fc#AuthOperation … result` and the frame bytes; if the device sends `(3,0x12)`/`(3,0x14)` echo with result data[0], encoding matches `§4`. Until then, the RX parser should accept *any* packet with data[0] as result code while logging unknown ids.

## 8. Device information

- Device info is a **device push**: `cfgId=2`, keyId `0x21` container (`Hd/q.java:45+` `_deviceInfo` log); inside it a **TLV container** `[key:u8][len:u8][value…]` where TLV key `0x11` = "device info" block. [V] the (2,0x21) wrapper and key-17 storage at `Fc#ConfigFeature` (`config2_17`).
- The key-17 block format was **not** mapped field-by-field here (behind `Qd.d` model). Verified source-of-truth values from the physical session: Project=`0000000052B6`, Patch=`000000000000`, Flash=`00000000`, App=`00000101`, IC type=`IC_8762C` (DiagnosticLog export). MVP surface: [V] capture raw blob; expose bytes; optional decode as a follow-up using `Qd.d` model. **Do not attempt to parse it as UTF-8 or hardwired known offsets** before it is mapped. [A]
- Device-info feature bits (`—isSupportFeature`, decoding of `h(284)/h(285)/h(286)/h(553)/h(588)`) — not part of MVP. [A]
- One quirk to preserve: device pushes already carry several config blocks (keyIds `0x09` family) and LCD-shape requests to the *device* fail for this variant on hardware (`requestLcdShape()` = NULL consistently). Keep the fail as an explicit, surfaced outcome. [V hardware]

## 9. What is NOT in scope / not yet reached by static analysis

- File/analog transmissions `0x9966` chunk family (`ffb1`,`ffb2` …) — beyond MVP. Not documented.
- Multi-chunk encrypted payload consistency quirk: TX in `U1.e.b()` restarts AES keystream per chunk (first chunk `maxLen−8`, then `maxLen`), but RX decrypts the reassembled frame **in one call** — the two differ whenever a frame's encrypted payload spans chunks and chunk boundaries are not 16-byte aligned. In practice P6 auth/config frames fit in one chunk. **Decision:** implement app TX exactly as `U1.e.b()` does (bit-for-bit parity with the reference), implement RX as the reference does (reassemble → whole-frame decrypt), and document that byte-level round-trip for oversized encrypted frames is unproven. [V code, A hardware relevance]
- Watch-face dial push, notifications/messages, alarms, music control — later phases.

## 10. Implementation contract for `commonMain/bluebit/fitcloud/native/`

Pure Kotlin (no JDK crypto — keeps `desktopTest` runnable and the stack portable):

| File | Contents |
|---|---|
| `P6TransportPacket.kt` | header/framing (`encode`/`decode`/ACK build/parse), CRC-16 table + routine, chunker |
| `Aes256BlockCipher.kt` | FIPS-197 AES-256 block encrypt (384-varied key schedule, no external deps) |
| `Aes256SessionCipher.kt` | session key/IV state + `encrypt`)`/decrypt`)= `o()` clone), one-block cyclic keystream |
| `P6KeyDerivation.kt` | §3.1 key/IV derivation (userId UTF-8 + MAC + constBlock) |
| `P6AuthPackets.kt` | BIND/LOGIN builds per §7 (incl. 32-byte uidBlock) |
| `P6Session.kt` | per-connection state: SN counter, last-RX-SN dedup, encrypted-flag mirror, reassembly buffer |
| `P6Connector.kt` | GATT orchestration on `bluebit.core.bluetooth.BleConnection` — discover, subscribe `ff03`, write via `ff02`, notify→parse→dispatch, auth handshake state machine |
| `P6ProtocolPacket.kt` | §4 serializer/parser (items list) |

Test rules: use **only** values demonstrably derived/excised (§2.2 model, §3 mode, §3.1 derives, §4 framing); any frame-bytes fixture assembled by following §2/§3/§4 arithmetic and cross-checked by hand; TODO hardware-captured frames later **replaces** assumptions if they differ. Tests must never assert upgraded packet turns (auth response layout, multi-chunk quirks).

## 11. Fog of interpretation — open items

1. Response keyIds of bind/login results (§7.4).
2. Frame-by-frame echo of entire config push structure (columns for (2,0x21)).
3. MTU value negotiation with P6 (`GATT_MTU_CHANGED` capture).
4. Multi-chunk encrypted frames (§9) — confirm whether P6 ever issues frames > (MTU−8).
5. The `payload[1]` role on device→app containers — receives "reserved" for cfgId 2/3/5/8 — decode only from captured frames.

## 12. Corrections to previous research

1. **ACK failure bit**: earlier REPORT v2 framed bit 0x20 of the flags byte as "ack-success". Evidence (`i9/b.java:322–337`: the app ACKs a *valid* packet via `a(false, sn)` → flags 0x30?? no — see below) actually resolves as follows: `U1/e.java:79` maps `a(z7=success, z10=isAck)` → flags `0x30` when z7 true; the RX loop acks valid packets with `a(false, sn)` ⇒ flags `0x10`, and CRC-failed/timeout packets with `a(true, sn)` ⇒ flags `0x30`; inbound acks from the device keep the sender retrying while 0x20 is set (`f54932m = !acked-fail`). Conclusion: **0x20-set = failure indicator** on ACKs in both directions; "success" ACK carries flags `0x10` only.
2. **Wire payload field at index 1**: earlier "byte[1] unknown" — verified to be `0x00` on TX (`p363zd/e.java:179` — `bArr3[1] = 0` for every outgoing protocol packet). RX-side `Hd/C0501l.java:26–27` reads byte[1] but does not use it when scanning items.
3. **CRC table identity**: `p339xe/j.java:35` table == canonical reflected table for poly 0x8005 (0xA001); init 0x0000 (both directions), no final xor. Earlier assumption of "CRC-16/IBM" holds for the polynomial while the (0-init) variance is confirmed not CRC-16/MODBUS (init 0xFFFF).
4. **Encryption nullification**: `p334x9.b.o()` is XOR-with-single-block-keystream encoded by the session key + an SN-conditioned IV; not AES-CTR with per-block counter — documented everywhere to avoid accidentally reimplementing true CTR.
5. **CRC is over plaintext in BOTH directions (decode order matters)**: §2.2 states the CRC field is computed over the plaintext payload before bulk encryption; the RX side must therefore **decrypt first, then verify the CRC over the decrypted bytes** (`U1/e.java` reads the frame, applies `Aes256CtrUtil.o()` to the reassembled payload, and the subsequent CRC check runs on plaintext). An implementation that CRCs the received ciphertext always NAKs encrypted frames — this exact defect was found and fixed in the native decoder (see `TEST_RESULTS.md`, decoder bug D2). Equally, TX-side encrypted sends must CRC the plaintext before encrypting (`encodeEncrypted`), never route ciphertext bytes back through the plaintext framer.
6. **AES-256 verification-vector pairing trap (test documentation)**: FIPS-197 appendix C example vectors all use the *sequential* key family with input `00112233...ee ff` (C.1 AES-128 key `000102...0f` → `69c4e0d86a7b0430d8cdb78070b4c55a`; C.2 AES-192 key `000102...1617` → `dda97ca4864cdfe06eaf70a0ec0d7191`; C.3 AES-256 key `000102...1f` → `8ea2b7ca516745bfeafc49904b496089`). The 32-byte key `603deb10...df f4` is the appendix **A.3 key-expansion illustration**, NOT an appendix C vector — pairing it with `8ea2b7ca...` is an unsatisfiable chimera (machine-verified: oracle + independent impl both return `d83414223d20a0c928b136c884d07ea2` for that pair, and decryption of `8ea2b7ca...` under that key yields `1fa5d691...`). This cost a debugging cycle; recorded here so the pairing is never re-assumed.

## 13. Reference packet walkthrough

This walkthrough is narrative, assembled by following §2/§3/§4 arithmetic. It is **not** a verified test fixture; every highlighted byte must be re-checked against a first hardware capture before it is promoted into a test assertion.

Example — open a LOGIN session on a fresh GATT connection (session starts plaintext):

1. First outgoing protocol packet: `payload = 03 00 14 00 00` (doLoginAuth probe, §7.2), SN = 1.
2. Framed (plaintext, flags 0x00, SN=1, CRC over the 5 payload bytes):
   `AA 00 00 05 <crc_hi> <crc_lo> 00 01 03 00 14 00 00`.
3. Device ACKs the probe: 8-byte ACK with SN=1 — flags `0x10` on success / `0x30` on failure (§2.1).
4. Device pushes the auth result as a data packet, e.g. `data[0] = 0x00` ⇒ success; send `(3, 0x13, uidBlock)` next, encrypted from this point on once the device's own frames carry the 0x40 bit (§2.3). The literal `(cmdId,keyId)` of the result push is flagged [A] in §7.4.

---

*End of specification v1 (static phase). Hardware-verification results will be appended as `PHASE-2-VALIDATION` annotations; any contradiction replaces the flagged [A] item and updates this doc.*
