# P6 Native Stack — Automated Test Results

> Automated-test evidence for the independent (non-SDK) FitCloud P6 stack as of 2026-11-04.
> Companion docs: `PROTOCOL_SPECIFICATION.md` (protocol claims), `IMPLEMENTATION_STATUS.md`
> (gap + design + bugs D1–D4), `KNOWN_LIMITATIONS.md` (what is not yet true).

## Run

- Command: `.\gradlew.bat :app:assembleDebug :app:desktopTest --console=plain`
  (task name is `:app:desktopTest` — `compileKotlinDesktopTest` does not exist as a task name).
- Result: **BUILD SUCCESSFUL**, `186 tests, 0 failed` (confirmed by summing
  `<testsuite>`/`<failure>`/`<error>` attributes across all `TEST-*.xml`),
  written 2026-11-04 19:44.
- Evidence: `BlueBitApp/app/build/test-results/desktopTest/TEST-*.xml`.
  Note: test `println` output does **not** appear on the Gradle console for this
  task; it is preserved in each XML's `<system-out>` element. Scratch/debug tests
  run via `--tests "bluebit.fitcloud.native.<Class>"` (the two scratch files
  `AesDebugScratchTest.kt` / `MirrorDebugScratchTest.kt` were deleted after use).
- The only build warnings are pre-existing deprecation warnings in untouched
  `androidMain` files (`FitbitGattlinkDiagnostic`, `FitbitGoldenGateDiagnostic`,
  `AndroidFitCloudProvider` BluetoothGatt accessors).

## F rules applied

- Only values demonstrably derived (spec §2/§3/§4 arithmetic, §3.1 key/IV
  derivation, decompiled constants) or authentic FIPS-197 vectors; **no invented
  expected bytes**. Wire fixtures were computed by hand following §2.2/§3/§4 and
  cross-checked against the table implementation.
- JDK `javax.crypto` was used as an independent oracle; the JDK implementation's
  own sanity was established first by passing the authentic FIPS-197 C.1/C.2
  vectors before any oracle comparison.

## Native suite (`bluebit.fitcloud.native.*`) — 72 tests / 0 failures

| Class | Tests | Covers |
|---|---|---|
| `Aes256BlockCipherTest` | 4 | Authentic FIPS-197 C.3 KAT, A.3 key-schedule cross-check, JDK-opaque round-trip |
| `P6AuthPacketsTest` | 4 | BIND/LOGIN probe + uidBlock submit layout (32-byte zero-padded UTF-8) |
| `P6ConnectorTest` | 11 | Full connect→auth state machine, ACK/retry, dedup, encrypted-flag mirror |
| `P6Crc16Test` | 8 | Table identity, streaming/incremental CRC, known digests |
| `P6DeviceInfoTest` | 4 | `(2,0x21)` inner TLV `[key][len][value]` parse |
| `P6KeyDerivationTest` | 8 | Session key `ef 97 ee b6 3e 03 96 6d …`, IV `d4 ef f0 d3 f4 e5 f0 90 eb 99 d6 5f 99 a1 00 00`, SN-in-IV |
| `P6ProtocolPacketCodecTest` | 7 | TX `[cmdId][0x00][keyId][len][data]`, RX item scan |
| `P6SessionCipherTest` | 5 | One-block cyclic-XOR keystream, self-inverse, SN-conditioned IV |
| `P6TransportPacketTest` | 21 | Framing, ACK build/parse, chunk TX quirk, stateful reassembly, decrypt-first CRC |
| **Total** | **72** | |

## Rest of suite (regression context — pre-existing code, untouched) — 114 tests / 0 failures

`BleServiceDataTest` 5, `BlueBitDeviceMatcherTest` 7, `DeviceServiceDataParserTest` 3,
`FitbitGattUuidsTest` 8, `GattlinkConnectorTest` 3, `GattlinkServiceSelectorTest` 4,
`GattlinkStartupSequenceTest` 2, `DevicePermissionPolicyTest` 7,
`ConnectionPresentationTest` 6, `AdvertisementVerifierTest` 3, `CoapReadOnlyProbeTest` 15,
`DtlsBootstrapProbeTest` 14, `DtlsProbeStateMachineTransientErrorTest` 12,
`GattlinkTxSerializerTest` 10, `FitbitProviderPairedEventTest` 7,
`PairedDeviceCoordinatorTest` 8. Native 72 + these 114 = **186**.

## Key wire fixtures asserted by tests (independently computed)

- LOGIN probe, TX SN 1, CRC field `0x0404`; frame parses as
  `magic AB | flags 00 | len 0005 | crc | sn 0001 | (3, 00, 14, 0000)`.
- LOGIN submit, TX SN 2, CRC `0xEDC3`; BIND submit, TX SN 2, CRC `0x2D98`;
  uidBlock = UTF-8("26544037") truncated to 28 → zero-padded to 32 (asserted
  byte-for-byte inside the submit payload).
- Device result pushes used in connector choreography: header
  `AB 00 00 06 <crc> 00 05` with CRC-field `0x5304` (LOGIN OK),
  `0x93C5` (bind-required), `0xDB04` (BIND OK) — devices SN 5.
- Post-auth plaintext send `(2,0x21,[01])`, SN 3, CRC `0x8ECB`
  (asserted identically in the retry and retransmit tests).
- Encrypted inbound frame header used to pin the mirror/decrypt path:
  `AB 40 00 06 71 CF 00 14 …`.

## Failure → fix chronology (drafts red, final green)

Chronological debugging history; every item below was resolved and its final
state is covered by the green suite (no failures remain).

1. **AES KAT chimera — test bug, not implementation bug.** The original
   known-answer test paired FIPS-197 appendix **A.3**'s key-expansion-illustration
   key `603deb10…df f4` with appendix **C.3**'s output `8ea2b7ca…4b496089` — an
   unsatisfiable pairing (A.3 vectors use input `00112233…ee ff`; C.3 uses key
   `000102…1f`). Machine-verified: the implementation *and* the JDK both produce
   `d83414223d20a0c928b136c884d07ea2` for that (key, input) pair, and decrypting
   `8ea2b7ca…` under the A.3 key yields `1fa5d6913d078bb72c278ab66e7f1858`. The
   JDK itself was proven sane by passing C.1/C.2 first. Fix: rewritten as the
   authentic C.3 KAT plus a separate A.3 schedule-cross-check
   (w8=`9ba35411`, w9=`8e6925af`). `Aes256BlockCipher.kt` was correct all along.
   Documented as spec §12.6.
2. **D1 — encrypted TX CRC field (real encoder bug).** `encodeEncrypted` routed
   the *ciphertext* back through the plaintext framer, so the CRC field held the
   CRC of ciphertext; the device would always NAK (spec §12.5). Fix: CRC the
   plaintext and build the header manually; the reference TX-chunk
   keystream-restart quirk is preserved (spec §9). Covered by
   `encodeEncrypted single chunk equals crypt then build with 0x40`,
   `chunked encrypted frames mirror the reference quirk and do not whole frame
   decrypt`, `non auth payload requested encrypted keeps the 0x40 flag`.
3. **D2 — RX decode order (real decoder bug).** The decoder computed the CRC
   *before* decryption, so every encrypted inbound frame failed CRC and was
   NAKed. Fix: decrypt first, then CRC over plaintext. Also Defence: zero-length
   DATA frames are rejected as `Invalid(sn, "zero length data frame")` ([A]) so
   the reassembler cannot wedge forever. Covered by
   `encrypted frames decrypt with the session cipher and surface the flag`,
   `crc mismatch is reported as invalid with the declared sn`,
   `zero length data frames are rejected defensively`.
4. **Test-side slicing/fixture defects (not implementation bugs).**
   - Incremental-reassembly tests sliced frames with a `copyOf`-vs-`copyOfRange`
     mix-up, shifting chunk boundaries; rewritten with explicit
     `full.copyOfRange(0, 8)` / `full.copyOfRange(8, size)` ranges
     (`frame split over notifications reassembles`,
     `feeding more bytes than declared overflows`).
   - Fixture bytes were first assembled through Kotlin `Int` arithmetic with
     `.toByte()` down-casts that silently truncated; recomputed from the
     documented arithmetic so the expected CRCs are derived (CRC table over the
     assembled plaintext) rather than hand-typed.
5. **D3 — ACK latch reuse (real connector bug).** The retry loop originally
   reused a single ACK `CompletableDeferred` across attempts, so a stale ACK
   could satisfy a later attempt and test scheduling was ambiguous; the interim
   write-count assertions drifted (a draft expected 3 → corrected to 2, another
   4 → 3) until the sequencing was settled. Fix: a fresh deferred is armed per
   attempt (`beginPendingSend`). Final, verified semantics:
   - `login flow writes probe then result ack then submit and authenticates`:
     exactly **3** characteristic writes — probe (SN 1), ACK of the device's
     result frame, submit (SN 2); CCCD written exactly once with `01 00`.
   - `send without device ack retries three attempts with the same sn and fails`
     and `nacks retransmit with the same sn until an accepted ack arrives`:
     **3 identical retransmits**, same SN 3, NACK (`acknowledge(3, failed=true)`)
     triggers the next attempt, success-ACK ends it.
6. **D5 — transport magic `0xAA` vs `0xAB` (caught physically, not by tests).**
   The decompile documents the header magic via `StatusIndex.ANC_MODE`, a Java
   `byte` = `-85` = `0xAB`; an earlier research session transcribed it as
   "0xAA" (`-86` is `0xAA` — off-by-one sign-byte confusion). Every unit fixture
   inherited `0xAA`, so the whole `desktopTest` suite passed with a wrong magic —
   encoder, decoder, ACK builder, and fixtures all agreed with each other and
   with the misdocumented spec §2. Hardware exposed it on the 2026-11-04 P6 run:
   three identical `AA`-headed probes (~585 ms apart, the exact ACK-wait retry
   cadence), zero `ff03` notifications, `P6ProtocolException: SN 1 failed after
   3 attempts`; on the same link minutes earlier the instrumented SDK run,
   sending `AB`-headed frames, received fields within ~100 ms
   (`AB 00 00 06 93 C5 00 73 | 03 00 14 00 01 01`). Fix: `MAGIC = 0xAB` in
   `P6TransportPacket.kt` + all 20 fixture occurrences in the two packet/connector
   test files; spec §12.7 (CRC fields unaffected — CRC covers payload only).
   Suite re-verified 186/0. Lesson: unit tests validate internal consistency;
   only hardware validates the wire.

## Status

Automated verification complete: implementation compiles on both targets
(`assembleDebug`, desktop) and all 186 tests pass. What remains is physical
validation against the P6 hardware — see `KNOWN_LIMITATIONS.md` §1.
