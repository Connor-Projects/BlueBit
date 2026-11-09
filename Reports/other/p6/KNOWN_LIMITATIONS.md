# P6 Native Stack — Known Limitations

> What the independent (non-SDK) FitCloud P6 stack does **not** yet do, and which
> claims are still assumptions. As of 2026-11-04. Companion docs:
> `PROTOCOL_SPECIFICATION.md` ([V]/[A] flags), `IMPLEMENTATION_STATUS.md`,
> `TEST_RESULTS.md` (automated evidence — the only verification that currently exists).

## 1. No physical handshake confirmation yet (biggest limitation)

Every protocol byte in the native stack is unit-verified against the derived
spec and authentic vectors, but the LOGIN/BIND handshake has **not** been run
natively against the physical P6 (`90:EB:99:D6:5F:99`). The pending validation
is the "Test Native P6 Login (non-SDK)" diagnostic action with debug identity
(userId `26544037`); the SDK path is known-good on the same device
(`doLoginAuth result:0`), which is the baseline the native stack must reproduce.

## 2. No HCI/btsnoop capture available on the A17

Samsung reverted the btsnoop setting to `empty`, so packet-level Bluetooth HCI
evidence cannot be captured on this phone. Physical validation evidence is
limited to `adb logcat` (tags `BlueBitP6Native`, `BlueBitBle`) and the app's
`DiagnosticLog` output. Frames used in tests were derived, not captured; a
future capture (rooted ROM, second phone, or an nRF Sniffer) *replaces*
assumptions if they differ (spec §10 test rules).

## 3. TX-chunk keystream quirk is mirrored, never "fixed" (spec §9 [V])

Outgoing encrypted payloads restart the AES keystream at every ATT chunk
(first chunk `limit − 8` payload bytes, later chunks `limit`), while incoming
encrypted payloads are decrypted as one whole reassembled buffer. This is a
deliberate mirror of the decompiled reference (`U1.e`) — it is asymmetric on the
wire and **oversized encrypted frames do not round-trip**. Never silently change
this; if hardware demonstrates different behaviour, capture it and re-derive
(spec §9). All current native sends fit a single chunk at MTU 247, so the quirk
path is dormant in the diagnostic flow.

## 4. `(cmdId, keyId)` of the auth-result push is [A] (spec §7.4)

The decompile does not pin down the exact `(cmdId, keyId)` carried by the
device's bind/login result push. The connector therefore treats the first
inbound packet inside the reference 15 s window (after the probe ACK) as the
result-bearing push and extracts `data[0]` as the result code; submission
completes on its own ACK. If hardware pushes something else first — or encodes
the result elsewhere — the connector may miss or misread it. This is the most
likely point for a first hardware run to disagree with the implementation.

## 5. CCCD enable bytes `{0x01, 0x00}` not re-confirmed in the native path

The connector writes one CCCD (`00002902`) with `{0x01, 0x00}` (asserted in
`P6ConnectorTest`) — the standard notification-enable value and consistent with
the GATT dump tooling, and the SDK path notifies on the same characteristic
on the same device — but the native write has not been observed on hardware.

## 6. Zero-length DATA frame rejection is [A] (defensive, not reference-backed)

`Invalid(sn, "zero length data frame")` is defensive engineering so a malformed
frame cannot wedge the reassembler indefinitely. ACK frames (flags `0x10`) are
filtered before this branch, so regular flow never hits it; a legitimate
zero-length data frame is currently believed impossible in this protocol. If
one ever appears, this behaviour must be revisited.

## 7. MTU / fallback path untested on hardware

`P6NativeDiagnostic` requests MTU 247 and threads `negotiatedMtu − 3` into the
connector; if the stack refuses, it logs the refusal and falls back to the
connector default (`DEFAULT_WRITE_LIMIT = 517`) — success is never faked. The
fallback path itself, and Android's actual `onMtuChanged` negotiation with the
P6, have not been exercised on hardware yet.

## 8. Integration gotchas verified but easy to miss

- **`0x3802` is advertisement-only [V]** — the advertised service `0x3802` does
  not appear in connected GATT (spec §1, P6 GATT dump); do not wait for it
  after connect.
- **Auth probes must never be encrypted** (`U1.e:76–78`): BIND/LOGIN probes and
  uidBlock submits go plaintext, enforced in the encoder and connector.
- **Device info push `(2,0x21)`** parser (`P6DeviceInfo`) is tested
  (`P6DeviceInfoTest`) but not hardware-verified.
- **Diagnostic toggle default OFF**: the P6 native action only appears when the
  master diagnostic toggle is on; it performs a real connect/login attempt each
  time (P6 pairing state is never torn down or reset by the probe).

## 9. Out of scope

Session establishment is the deliverable; post-auth commerce (device info
pull, LCD shape, dial push, sync flows) is intentionally not implemented by the
native stack — the SDK provider path remains side-by-side unchanged for those.
