# FitCloudPro Authentication Analysis

**Generated:** 2026-10-30 (v2 — corrected after source-verification pass)
**Source:** Static analysis of `com.topstep.fitcloudpro` APK (JADX 1.5.6 + Apktool 2.11.0)
**Hardware cross-check:** `Reports/FitCloud_P6_Validation_Report.md` (P6 login test, 2026-10-29)

> v1 of this analysis attributed the wrong command IDs (0x12/0x14/0x15 from Realsil `Mmi`
> constants) to bind/login and mischaracterised encryption as XOR-only. All protocol values below
> re-derive from the FitCloud classes themselves.

---

## 1. Overview

FitCloudPro's tracker authentication is an **id-key announcement protocol** built on top of a
framed transport (`Fc#TransportPacket`). There is no cryptographic key agreement: the client
sends packets derived from its `userId`; the device associates the binding with that userId and
accepts later LOGIN attempts that present the same userId. Session "keys" (an AES-256 key and a
CTR nonce) are **derived on the phone from public inputs** — userId bytes, the device MAC, and
fixed constants — and the key-material packets are transmitted **unencrypted**.

---

## 2. Auth Architecture

| Class | Decompiled name | Role |
|-------|-----------------|------|
| Connector | `p150kd.j` (tag `Fc#Connector`) | RxAndroidBle connect, GATT config, session keys, SP storage |
| Auth operation | `p026be.f` (tag `Fc#AuthOperation`) | BIND/LOGIN sequencing, token persistence |
| Auth mode enum | `p181me.a` | `BIND` / `LOGIN` / `AUTO` |
| Connection state | `p181me.d` | DISCONNECTED → CONNECTING → CONNECTED → … |
| Protocol packet | `p349yd.a` | `FcProtocolPacket(cmdId, keyId, keyData)` |
| Key/IV + AES util | `p334x9.b` | `k()` key-packet builder; `o()` `Aes256CtrUtil` |
| Token generator | `p026be.f.d()` | base-36 timestamp token, SP `token2_<mac>` |

Transport payload = `FcProtocolPacket(cmdId, keyId, keyData[])` — verbatim from
`p349yd.a.toString()`: `"FcProtocolPacket(cmdId=%#x, keyId=%#x, keyData=[…])"`.

---

## 3. Auth state machine (`p026be/f.java`)

| Field | Meaning |
|-------|---------|
| `f18015b` | `firstAuth` (init `true`) — forces BIND on the first connection |
| `f18016c` | stored `userId` (init `""`) |
| `f18017d` | current AuthMode (default `AUTO`) |
| `f18018e` | volatile first-attempt flag |
| `f18019f` | volatile session token string |

Mode resolution at connect (`p150kd/j.java:271–306`):

- `bindOrLogin=true` → `BIND` (`p181me.a.f45453a`); `false` → `LOGIN` (`f45454b`).
- If the requested `userId` differs from the stored one: `firstAuth` is forced `true`, and the
  connect resets `syncTimes<N>` SP keys and the `epo2_` EPO timestamp (`p150kd/j.java:307–325`).

---

## 4. Connection & auth sequence

### 4.1 GATT layer

Connect through RxAndroidBle to the recorded device address. Notify (`ff03`) and write (`ff02`)
characteristics come from the connector config (`p150kd/j.java:155–161`, service
`000001ff-3c17-d293-8e48-14fe2e4da212`). The device's CCCD is enabled on the notify
characteristic before packets flow.

### 4.2 BIND (`AuthOperation.e()`, `p026be/f.java:110–116`, tag `doBindAuth`)

| Packet | Content |
|--------|---------|
| 1 | `FcProtocolPacket(cmdId=0x03, keyId=0x12, no data)` |
| 2 | `FcProtocolPacket(cmdId=0x03, keyId=0x11, keyData=userIdBlock32)` — built by `p334x9.b.k(userId, true)` |

`userIdBlock32` = 32 bytes; the userId string bytes are copied (max 28) zero-padded
(`p334x9/b.java:452–460`). Timeout: 15 s.

### 4.3 LOGIN (`AuthOperation.f()`, `p026be/f.java:118–123`, tag `doLoginAuth:<retry>`)

| Packet | Content |
|--------|---------|
| 1 | `FcProtocolPacket(cmdId=0x03, keyId=0x14, no data)` |
| 2 | `FcProtocolPacket(cmdId=0x03, keyId=0x13, keyData=userIdBlock32)` — built by `p334x9.b.k(userId, false)` |

Timeout: 15 s. (Exact transmit ordering of the two packets is not fully resolved from the
sequencing helper classes; both belong to the named sequence.)

### 4.4 Success / token

`AuthOperation.d(mac)` (`p026be/f.java:100–108`):

```java
String token = Long.toString(System.currentTimeMillis(), 36);   // base-36 timestamp
prefs.edit().putString("token2_" + mac.replace(":", ""), token).apply();
```

Stored in SharedPreferences `fc_internal_storage` (file name from `p150kd/j.java:163`).

**Hardware cross-check (P6, 2026-10-29):** logs recorded
`Auth login: mu5z25th` and `doLoginAuth result:0` with `bindOrLogin=false` and
`userId=26544037` — the token format matches `d()` exactly (base-36 millisecond timestamp), and
LOGIN succeeded only with the userId under which the official app had bound the watch. The same
tag names (`Fc#Connector`, `Fc#AuthOperation`) appear in both the decompiled code and the
hardware logs.

### 4.5 One more connector frame

At connector construction, one boot command is queued: `FcProtocolPacket(cmdId=0x02, keyId=0x50,
payload=u16(3))` (`p150kd/j.java:203–207`, keyId from `Mmi.AU_MMI_DISC_LINK_AND_ENTER_PAIRING_MODE`
= 80/0x50). Its semantic (likely pairing/protocol-version boot) is inferred, not confirmed.

---

## 5. Session key derivation (verified `p150kd/j.java:382–424`)

**Inputs:** `userId` string, device MAC (`aa:bb:cc:dd:ee:ff`).

```text
userIdBlock[32] = userId bytes (UTF-8), zero-padded, max 28 copied
mac[6]          = MAC octets
constBlock[32]  = { 0x4D, 0x4A, 0x42, 0x54, 0x55, 0xAA, 0xA5, 0x5A } × 4
                  ("MJB", 0x54='T', 0x55='U', 0xAA, 0xA5, 0x5A='Z')

AES-256 key p334x9.b.f54121f[32]:
   key[i] = (i < 6 ? mac[i] : 0x00) ^ userIdBlock[i] ^ constBlock[i]

Nonce p334x9.b.f54120e[16]:
   { 0xD4, 0xEF, 0xF0, 0xD3, 0xF4, 0xE5, 0xF0,
     mac[0..5], 0xA1, 0x00, 0x00 }
```

Lifecycle: assigned during connect post-auth (lines 417/424), nulled at disconnect
(lines 256–257). Per-packet the CTR counter is the transport sequence number
(`IV[14]=sn>>8; IV[15]=sn&0xFF` — `p334x9/b.java:486–489`).

---

## 6. Encryption behaviour on the auth flow

Verified in `U1/e.java`:

- Packet header flag bit `0x40` marks an encrypted frame; parsing sets the global
  `f12360g` enable flag from the **received** header (`U1/e.java:167`).
- Outgoing frames are encrypted only while `f12360g` is set.
- **Key packets are exempt:** when the outgoing payload is the auth key packet
  (`payload[0]==3 && payload[2]==17 || payload[2]==19` — cmdId `0x03`, keyId `0x11`/`0x13`),
  `f12360g` is force-cleared (`U1/e.java:76–78`), so the key material goes on-air plaintext.

Consequence: anyone sniffing the connection sees the key-derivation input (userId block) in the
clear, and everything else is protected only by a key derived from public values.

---

## 7. Transport layer security summary

| Layer | Mechanism | Status |
|-------|-----------|--------|
| GATT attribute permissions | None on P6 (all `NONE(0x0)`) | Hardware dump |
| Link layer (LE pairing/bonding) | Not required; P6 usable unpaired by GATT dump | Hardware dump |
| Packet framing | `Fc#TransportPacket`: `0xAA` magic, flags (0x10 ack-marker / 0x20 ack-success / 0x40 encrypted), len BE16, CRC16 BE16, SN BE16 | `U1/e.java` |
| Payload encryption | AES-256-CTR, static key/IV + SN counter | `p334x9.b.o()` |
| Application AES | Cloud API / HMS / ShareSDK only (TLS + AES elsewhere) — no relation to BLE frames | Various |

---

## 8. Security assessment

| Aspect | Rating | Notes |
|--------|--------|-------|
| Cipher strength | ✅ AES-256 | Real cipher, properly implemented CTR |
| Key secrecy | ❌ None | Key derived from public inputs (userId, MAC, constants); no server contribution |
| Forward secrecy | ❌ None | Static inputs → static keys |
| Replay protection | ⚠️ Weak | 16-bit SN only; also used as CTR counter |
| Man-in-the-middle | ❌ No protection | No authentication of the device (the "key announcement" can be replayed) |
| MITM of key material | ❌ Plaintext | Key packets transmitted unencrypted (`U1.e:76–78`) |
| GATT bonding | Not required | P6 exposes all chars with permission `NONE` |
| Overall | Obfuscation-grade | Defeats only a "casual snooper who doesn't know the userId"; an attacker who has the MAC + userId (both public/on-air in the key packet) can derive or obtain all keys |

---

## 9. Comparison: FitCloudPro vs Fitbit Inspire 3 auth

| Aspect | FitCloudPro | Fitbit Inspire 3 |
|--------|-------------|------------------|
| Protocol family | Topstep FcProtocolPacket + OxFF transport | Gattlink + GoldenGate |
| Auth model | BIND/LOGIN announcing a userId-derived key block | Ephemeral key exchange + DTLS bootstrap |
| Encryption | AES-256-CTR, statically derivable key | DTLS 1.2 / AES-GCM with negotiated keys |
| Key exchange | None (key packets plaintext) | ECDHE via DTLS handshake |
| Certificates | None | X.509 (Fitbit infra) |
| Reconnection | LOGIN with the same userId | DTLS session resumption |
| Verdict | Different at every layer | Not portable |

**Conclusion:** BlueBit's Inspire 3 authentication blocker (Gattlink ephemeral keys/DTLS) has no
overlap with, or shortcut from, the FitCloud protocol.

---

## 10. Key source references

| Component | Path | Lines |
|-----------|------|-------|
| Auth operation | `research/fitcloud-apk/decompiled/jadx/sources/p026be/f.java` | 26–124 |
| Connector connect/auth | `…/sources/p150kd/j.java` | 128–218, 271–379 |
| Key derivation | `…/sources/p150kd/j.java` | 382–428 |
| Key packet builder | `…/sources/p334x9/b.java` | 452–460 |
| AES-256-CTR util | `…/sources/p334x9/b.java` | 476–516 |
| Token/SP | `…/sources/p026be/f.java` | 100–108 |
| Transport frame | `…/sources/U1/e.java` | 45–147, 149–202 |
| Protocol packet type | `…/sources/p349yd/a.java` | 9–43 |
| Constant tables | `…/sources/com/realsil/sdk/bbpro/params/Mmi.java` (values 0x50/0x54/0x55/0x5A/0xD3/0xD4/0xF0), `…/params/StatusIndex.java` (0xAA, 0xA5) | — |

---

## 11. Confirmed vs inferred

| Finding | Status |
|---------|--------|
| BIND `(0x03,0x12)` + key `(0x03,0x11)`; LOGIN `(0x03,0x14)` + key `(0x03,0x13)` | ✅ Confirmed (`p026be/f.java:110–123`) |
| Key packets plaintext | ✅ Confirmed (`U1/e.java:76–78`) |
| Session token = base-36 ms timestamp in `token2_<mac>` | ✅ Confirmed (`p026be/f.java:100–108` + hardware token `mu5z25th`) |
| userId mismatch forces re-BIND and clears sync state | ✅ Confirmed (`p150kd/j.java:289–325`) |
| AES-256-CTR with derived key/IV | ✅ Confirmed (`p334x9/b.java:476–516`, `p150kd/j.java:382–424`) |
| Challenge-response between app and device | ❌ Not found — the "exchange" is one-directional key announcement |
| Certificate pinning / nonce freshness / HMAC | ❌ Not found |
| Exact wire byte order of `FcProtocolPacket` fields and packet-boundary rules | ⚠️ Partially verified (positions inferred from the encryption-bypass check and ACK offsets) |
| Purpose of the boot command `(0x02, 0x50, u16 3)` | ⚠️ Inferred (bind/protocol-version handshake) |

---

## 12. Unanswered questions

1. Does the device ever set the `0x40` flag (post-auth encrypted mode), or do sessions stay
   plaintext from the device side?
2. Exact serialization of `FcProtocolPacket` (byte position of `cmdId` vs `keyId` in the enclosed
   payload; the bypass check implies `payload[0]=cmdId`, `payload[2]=keyId` with an unknown
   `payload[1]`).
3. Token TTL / whether the device ever validates `token2_<mac>` itself.
4. Whether LOGIN retry (`doLoginAuth:<retry>` variants) changes the packet sequence.
5. Whether the cloud API reuses the on-device key material (out of scope for BLE research).

---

*End of Authentication Analysis (v2).*
