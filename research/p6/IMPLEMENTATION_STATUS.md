# P6 Native Stack — Implementation Status

> Status of the independent (non-SDK) FitCloud P6 integration as of 2026-11-04.
> Companion docs: `PROTOCOL_SPECIFICATION.md` (protocol claims, [V]/[A] flags),
> `TEST_RESULTS.md` (automated test evidence), `KNOWN_LIMITATIONS.md` (what is not
> yet true). Hardware validation report: `Reports/FitCloud_P6_Validation_Report.md`.

## What already existed (untouched, pre-existing P6 functionality)

- **SDK-provider path** (`AndroidFitCloudProvider` + `com.topstep.wearkit:sdk-fitcloud`
  3.0.2.4): connect/login, device-info/LCD-shape/dial/UI queries. Kept side-by-side
  byte-for-byte; its diagnostics actions in the Developer screen still work.
- Hardware baseline from `Reports/FitCloud_P6_Validation_Report.md`: SDK
  `doLoginAuth` returns `result:0` for userId `26544037` on device
  `90:EB:99:D6:5F:99`; `requestLcdShape()` fails on that firmware (IC_8762C) —
  this was the motivation for the independent stack.
- Generic `BleConnection` abstraction already exposed `writeDescriptor` (CCCD path).

## What was missing (the gap this implementation closes)

Everything under `app/src/commonMain/kotlin/bluebit/fitcloud/native/` is new:

| File | Role |
|---|---|
| `P6Crc16.kt` | Table-driven CRC-16 (poly `0x8005` reflected, init `0x0000`), byte-for-byte equal to the decompiled APK table |
| `Aes256BlockCipher.kt` | FIPS-197 AES-256 encrypt (no javax.crypto dependency in `commonMain`) |
| `P6SessionCipher.kt` | The `Aes256CtrUtil` transform: one AES block of keystream, cyclic XOR, self-inverse |
| `P6KeyDerivation.kt` | Session key/IV from userId + MAC + constant block (`p150kd/j.java:382–424`) |
| `P6ProtocolPacket.kt` | Wire protocol packet codec: TX `(cmdId, 0x00, keyId, len, data)`, RX container of keyed items |
| `P6AuthPackets.kt` | BIND/LOGIN probes + 32-byte uidBlock submits, result-code descriptions |
| `P6TransportPacket.kt` | `0xAB` transport framing, ACK build/decode, chunked AES TX, stateful RX reassembler |
| `P6SessionState.kt` | SN counters (TX start at 1, RX dedup), pending-ACK latches via `CompletableDeferred`, encrypted-flag mirror |
| `P6Connector.kt` | FULL session orchestration over `BleConnection`: connect → MTU hook → discover → verify transport chars → subscribe `ff03` + CCCD `{0x01,0x00}` → auth → send/receive loop |
| `P6DeviceInfo.kt` | Inner TLV `[key:u8][len:u8][value]` parser for the `(2,0x21)` device-info push |

Android-side additions:

- `BleConnection.requestMtu(mtu): Boolean = false` (default) + real implementation in
  `AndroidBleConnection` (`onMtuChanged` completes the deferred; `negotiatedMtu` exposed).
  Non-breaking: `NoOpBleConnection`, `FakeBleConnection`, `FitCloudSdkBleConnection` inherit the default.
- `bluebit/fitcloud/diagnostics/P6NativeDiagnostic.kt`: on-demand handshake probe
  wired as the "Test Native P6 Login (non-SDK)" action behind the diagnostic master
  switch (default OFF). It negotiates MTU 247 and threads `negotiatedMtu − 3` into
  the connector; if the stack refuses, it logs the refusal and falls back to the
  default ceiling — success is never faked.

## Auth state machine (enabled behaviour)

1. Probe `(3,0x14,∅)` (LOGIN) — plaintext, SN 1.
2. Device ACKs; first inbound data packet within the reference 15 s window carries
   the result code; `data[0]`: `0` OK / `1` bind-required / `2,3,5` errors.
3. Submit `(3,0x13,uidBlock)` uidBlock = UTF-8(userId) truncated to 28, zero-padded
   to 32. Submit **completes on its ACK** (reference `p026be/f.java` semantics; the
   `(cmdId,keyId)` of the result push stays [A], spec §7.4).
4. Auth submits are NEVER AES-encrypted (`U1/e.java:76–78`), enforced in both
   `P6TransportPacket.encode` and the connector's send path.
5. Subsequent sends encrypt when the **inbound-mirror** flag is set (reference
   `U1.e.f12360g`). Known reference quirk is mirrored, not "fixed": TX restarts the
   keystream per ATT chunk while RX decrypts the whole reassembled payload in one
   call (spec §9) — oversized encrypted frames therefore do not round-trip; every
   auth/config frame fits a single chunk at MTU 247.

## Session correctness notes (bugs found and fixed during bring-up)

- **D1 — encrypted TX CRC field**: `encodeEncrypted` originally routed encrypted
  bytes through the plaintext framer, so the CRC field held the CRC of
  *ciphertext*. Per spec §2.2/§12.5 the CRC is always over plaintext; the framer
  now builds the frame directly.
- **D2 — RX decode order**: the decoder originally computed the CRC *before*
  decryption, so every encrypted inbound frame NAKed with "crc mismatch"; now
  decrypt-first, then CRC over plaintext (spec §12.5).
- **D3 — ack latch reuse**: the retry loop now arms a fresh `CompletableDeferred`
  per attempt (`beginPendingSend`), so a stale ACK cannot satisfy a later attempt
  and test scheduling stays deterministic.
- **D4 — zero-length data frames** are rejected defensively (`Invalid(sn, "zero
  length data frame")`) instead of wedging the reassembler forever. ACK frames
  (flags `0x10`) are filtered before this branch, so regular flow is unaffected.
  [A] — defensive engineering, not reference evidence.

## Deliberate boundaries

- `AndroidFitCloudProvider` and `FitCloudDialInspector` untouched.
- Inspire 3 (Gattlink/GoldenGate/DTLS/CoAP) untouched.
- No new Gradle dependencies; pinned `com.topstep.wearkit` untouched.
- Diagnostic master toggle default OFF; the new P6 action only appears when it is
  enabled, and it is a real connection attempt each time — no caching of previous
  results.
- Auth identity stays the hardware-verified `userId 26544037`; the MAC comes from
  the BLE address of the device actually connected and is uppercase-normalized.
