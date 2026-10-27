# Fitbit Inspire 3 BLE -> IPv4/UDP/DTLS Protocol Analysis

## 1. Executive summary

This report reverse-engineers the Fitbit Inspire 3 communication protocol from the
offline captures in `G:\Dev\BlueBit`.  The capture contains a complete BLE ATT
exchange (`successful_sync_exchange.tsv`) and a derived inner-traffic PCAP
(`inspire3_inner_traffic.pcap`).

Key findings:

* The protocol stack is **BLE ATT -> custom per-PDU framing -> IPv4 -> UDP -> DTLS**.
* IPv4 packets are carried inside ATT Write Commands (phone -> tracker) and ATT
  Notifications (tracker -> phone) on two Golden-Gate data characteristics.
* Each ATT value is prefixed by a **1-byte sequence/control byte**.
  * `0x00-0x7F` = data PDU (may be empty = ACK).
  * `0x80` = empty link-control event.
  * `0x81` = MTU advertisement (`00000808` = 2056 bytes, big-endian).
* Data PDUs are concatenated per direction and sliced into IPv4 packets using the
  IPv4 total-length field.  No extra length field is used.
* The link-local endpoints are **phone = 169.254.0.2** and **tracker = 169.254.0.3**,
  both using UDP port **5684**.
* DTLS version is **1.2 (`0xfefd`)**, cipher suite **TLS_PSK_WITH_AES_128_CCM
  (`0xC0A4`)**.
* Three DTLS handshakes are visible:
  1. First attempt with PSK identity `MD-A2CB0B8E-6AE5D947` is rejected with
     `unknown_psk_identity`.
  2. Successful bootstrap with identity `BOOTSTRAP`.
  3. A MobileData re-key/resumption with identity `MD-A376B03C-6AF89570`.
* 329 encrypted application-data records are exchanged after the bootstrap
  handshake; the payloads remain encrypted and are **not decoded**.
* The TSV reconstruction is internally consistent and matches 300 PCAP frames
  exactly. 30 PCAP frames are truncated/malformed and 11 reconstructed packets
  are missing from the PCAP, confirming the TSV should be treated as the
  authoritative byte source.

All work was performed offline on the supplied files.  No captured secrets were
sent to external services.

## 2. Files used

| File | Purpose |
|------|---------|
| `successful_sync_exchange.tsv` | UTF-16 LE TSV of every ATT write/notify carrying the IPv4 byte stream. |
| `inspire3_inner_traffic.pcap` | Derived little-endian PCAP (DLT_RAW=101) of the reconstructed IPv4 traffic. |
| `successful_sync.log` | btsnoop HCI log, used only for wall-clock timestamps via tshark. |
| `analyse_inspire3_capture.py` | Standard-library Python script that reproduces all parsing, reconstruction, and CSV/PCAP outputs. |

Outputs are written to `G:\Dev\BlueBit\inspire3_analysis_output\`.

## 3. Methodology

### 3.1 ATT TSV parsing

* 833 rows total; 831 carry a non-empty ATT value.
* `0x52` writes to handle `0x0042` are **phone -> tracker**.
* `0x1b` notifications from handle `0x0044` are **tracker -> phone**.
* The first byte of every value is a **per-PDU sequence/control prefix**.

### 3.2 IPv4 reassembly

For each direction the script:

1. Strips the prefix byte from every data PDU (`prefix < 0x80`).
2. Concatenates payloads in capture order.
3. Treats zero-length data PDUs as ACKs (they do not advance the byte cursor).
4. Slices the byte stream into IPv4 packets using the 16-bit IPv4 total-length
   field.

This produced **178 phone->tracker** and **163 tracker->phone** IPv4 packets with
**zero leftover bytes**.

### 3.3 DTLS parsing

The script walks the UDP payload of each IPv4 packet and parses DTLS record
headers (content type, version, epoch, sequence number, length).  Handshake
records are further inspected to extract handshake type and, for
ClientKeyExchange, the PSK identity.

### 3.4 PCAP correlation

Reconstructed packets are matched to the supplied PCAP by `(src, dst, ip_id)`.
The PCAP frame is considered:

* **exact** if it is byte-identical to the reconstructed packet;
* **truncated/malformed** if it is a prefix of the reconstructed packet;
* **missing** if no matching PCAP frame exists.

## 4. Results

### 4.1 ATT / link-control summary

| Metric | Value |
|--------|-------|
| Total ATT PDUs parsed | 831 |
| phone -> tracker PDUs | 360 |
| tracker -> phone PDUs | 471 |
| Link-control PDUs (`0x80`/`0x81`) | 10 |
| IPv4 packets phone -> tracker | 178 |
| IPv4 packets tracker -> phone | 163 |

Link-control events observed:

| BLE frame | Direction | Prefix | Interpretation |
|-----------|-----------|--------|----------------|
| 12157 | tracker->phone | `0x80` | link-control (empty) |
| 12158 | phone->tracker | `0x80` | link-control (empty) |
| 12160 | phone->tracker | `0x81` | MTU advertise **2056** |
| 12161 | tracker->phone | `0x81` | MTU advertise **2056** |
| 12197 | tracker->phone | `0x80` | link-control (empty) |
| 12200 | phone->tracker | `0x81` | MTU advertise **2056** |
| 12212 | tracker->phone | `0x81` | MTU advertise **2056** |
| 15236 | tracker->phone | `0x80` | link-control (empty) |
| 15237 | phone->tracker | `0x81` | MTU advertise **2056** |
| 15238 | tracker->phone | `0x81` | MTU advertise **2056** |

The `0x81` payload `00000808` (big-endian) decodes to 2056, which matches the
Golden Gate Gattlink/DTLS maximum PDU size documented elsewhere in the BlueBit
repo.

### 4.2 IPv4 / UDP endpoints

| Field | phone | tracker |
|-------|-------|---------|
| IPv4 source | 169.254.0.2 | 169.254.0.3 |
| IPv4 destination | 169.254.0.3 | 169.254.0.2 |
| UDP source port | 5684 | 5684 |
| UDP destination port | 5684 | 5684 |

### 4.3 DTLS handshake timeline (chronological by BLE frame)

Records other than application data:

| BLE frame | Direction | IP ID | DTLS record | Details |
|-----------|-----------|-------|-------------|---------|
| 12167 | tracker->phone | 0 | ClientHello | version `0xfefd`, suites include `TLS_PSK_WITH_AES_128_CCM` |
| 12174 | phone->tracker | 0 | ServerHello | selected `TLS_PSK_WITH_AES_128_CCM` |
| 12174 | phone->tracker | 0 | ServerHelloDone | |
| 12184 | tracker->phone | 1 | ClientKeyExchange | PSK identity `MD-A2CB0B8E-6AE5D947` |
| 12184 | tracker->phone | 1 | ChangeCipherSpec | |
| 12184 | tracker->phone | 1 | Finished (encrypted) | epoch 1 |
| 12186 | phone->tracker | 1 | Alert | level 2, `unknown_psk_identity` |
| 12221 | tracker->phone | 2 | ClientHello | second attempt |
| 12228 | phone->tracker | 2 | ServerHello | selected `TLS_PSK_WITH_AES_128_CCM` |
| 12228 | phone->tracker | 2 | ServerHelloDone | |
| 12241 | tracker->phone | 3 | ClientKeyExchange | PSK identity `BOOTSTRAP` |
| 12241 | tracker->phone | 3 | ChangeCipherSpec | |
| 12241 | tracker->phone | 3 | Finished (encrypted) | epoch 1 |
| 12247 | phone->tracker | 3 | ChangeCipherSpec | |
| 12247 | phone->tracker | 3 | Finished (encrypted) | epoch 1 |
| 13219 | tracker->phone | 41 | ClientHello | MobileData re-key/resumption |
| 13226 | tracker->phone | 42 | ClientKeyExchange | PSK identity `MD-A376B03C-6AF89570` |
| 13226 | tracker->phone | 42 | ChangeCipherSpec | |
| 13226 | tracker->phone | 42 | Finished (encrypted) | epoch 1 |
| 14953 | phone->tracker | 41 | ServerHello | selected `TLS_PSK_WITH_AES_128_CCM` |
| 14953 | phone->tracker | 41 | ServerHelloDone | |
| 14964 | phone->tracker | 42 | ChangeCipherSpec | |
| 14964 | phone->tracker | 42 | Finished (encrypted) | epoch 1 |

The first handshake fails because the tracker does not accept the
`MD-A2CB0B8E-6AE5D947` PSK identity.  The tracker then falls back to the
hard-coded `BOOTSTRAP` identity and succeeds.

**Anomaly:** for the `MD-A376B03C-6AF89570` handshake, the tracker sends
ClientHello (13219) and ClientKeyExchange/Finished (13226) before the phone's
ServerHello appears in the TSV at frame 14953.  The derived
`inspire3_inner_traffic.pcap` places these four records contiguously as PCAP
frames 81-84, with PCAP frame 82 matching the phone->tracker ServerHello content
at BLE frame 14953.  This is most likely a capture/fragment-reassembly artifact:
the phone's response was either buffered, delayed in the TSV, or retransmitted.
The byte content of the handshake is consistent; only the TSV wall-clock ordering
is anomalous.

### 4.4 Encrypted application data

* First encrypted application-data record: **BLE frame 12253**, phone->tracker,
  epoch 1, sequence 1.
* Total application-data records parsed: **329**.
* The records are opaque; no keys are available, so they are **not decrypted**.

### 4.5 PCAP comparison

| Status | Count |
|--------|-------|
| Exact match | 300 |
| Truncated / malformed in PCAP | 30 |
| Unmatched PCAP frames | 0 |
| Reconstructed packets missing from PCAP | 11 |

The 30 truncated frames are all **large tracker->phone application-data packets**
that the PCAP tool sliced to 77 bytes while the IPv4 header still claims the full
length (~1101 bytes).  These match the 30 frames that tshark marks as
`[Malformed Packet: DTLS]`.

The 11 missing reconstructed packets are mostly additional large
phone->tracker and tracker->phone data frames that were dropped or not
reassembled by the PCAP generator.

Because the TSV reconstruction is byte-perfect and contains no leftover bytes,
**the TSV reconstruction is the authoritative source** for this capture; the PCAP
is useful only as a cross-check.

## 5. Discussion

### 5.1 Facts vs hypotheses

| # | Fact | Evidence |
|---|------|----------|
| 1 | ATT payload is prefixed by a 1-byte sequence/control field. | Every `btatt.value` begins with a byte < 0x80 for data or 0x80/0x81 for control; zero-length prefixes are ACKs. |
| 2 | IPv4 packets are delimited only by the IPv4 total-length field. | Reassembly yields 341 packets and zero leftover bytes. |
| 3 | Link-local addresses are 169.254.0.2/3 and UDP port is 5684. | Parsed from every IPv4/UDP header. |
| 4 | DTLS uses TLS_PSK_WITH_AES_128_CCM. | ClientHello/ServerHello cipher-suite lists and selection. |
| 5 | The tracker first tries a per-device MobileData PSK identity, then falls back to `BOOTSTRAP`. | `MD-A2CB0B8E-6AE5D947` rejected; `BOOTSTRAP` accepted. |
| 6 | A second MobileData identity `MD-A376B03C-6AF89570` is used later. | ClientKeyExchange at frame 13226. |
| 7 | Application data is encrypted and not decoded. | Content type 23, epoch 1; no keys in capture. |

| # | Hypothesis / inference | Confidence |
|---|------------------------|------------|
| 1 | The 0x80/0x81 exchange negotiates/acknowledges an inner MTU of 2056 bytes. | High (payload `00000808` = 2056 appears at every session start). |
| 2 | Empty data PDUs (`0x40`, `0x41`, `0x43`, `0x4c`, `0x45`) acknowledge reception up to that sequence number. | Medium (they occur at packet boundaries and after large multi-fragment transfers). |
| 3 | The `BOOTSTRAP` PSK is the hard-coded 16-byte key from `BootstrapTlsKeyResolver.kt` in the BlueBit/Fitbit artifacts. | Medium-High (identity matches; key itself is not present in the capture, only the identity string). |

### 5.2 Authentication inferences

* The DTLS handshake is **PSK-based**, not certificate-based.
* The tracker acts as the DTLS client (it sends the first ClientHello and the
  ClientKeyExchange).
* The phone acts as the DTLS server (it sends ServerHello/ServerHelloDone and
  the final Finished).
* The PSK identity format for MobileData sessions is **`MD-XXXXXXXX-XXXXXXXX`**
  (hex ASCII), matching the repository documentation.
* The capture reveals only PSK **identities**, not the secret keys.  The
  `BOOTSTRAP` secret key is known from the companion-app/APK reverse-engineering
  work already in the BlueBit repository; it is **not derived from this capture**.

### 5.3 Unknowns

1. The exact meaning of every prefix value (sequence numbers, ACK semantics,
   retransmission rules).
2. Whether the 12-second gap around the `MD-A376B03C-6AF89570` handshake is a
   real delay, a capture artifact, or a retransmission.
3. The CoAP message format inside the encrypted application data.
4. How the MobileData PSK identities map to the 16-byte keys used with
   `TLS_PSK_WITH_AES_128_CCM`.
5. The purpose of the link-control reset at frame 15236.

### 5.4 Suggested next experiments

1. **Parse the framing prefix semantics** by correlating prefix values with
   multi-fragment packet boundaries and empty ACKs.  The script already emits
   `control_events.csv` and `reconstructed_packets.csv` for this.
2. **Decrypt a test packet offline** using the known `BOOTSTRAP` PSK and the
   DTLS nonce/sequence numbers from the bootstrap handshake.  This would confirm
   the key derivation and reveal the CoAP payload structure.
3. **Capture a second sync session** from the same paired device and compare the
   MobileData PSK identities; this will show whether identities are stable per
   device or change per session.

## 6. Script usage

Run from `G:\Dev\BlueBit`:

```powershell
python analyse_inspire3_capture.py
```

Optional arguments:

```
python analyse_inspire3_capture.py --tsv successful_sync_exchange.tsv \\
                                   --pcap inspire3_inner_traffic.pcap \\
                                   --btsnoop successful_sync.log \\
                                   --tshark "C:\Program Files\Wireshark\tshark.exe" \\
                                   --outdir inspire3_analysis_output
```

`tshark` is used only to extract wall-clock timestamps from `successful_sync.log`;
if it is unavailable the script still produces all byte-level outputs with empty
`timestamp` columns.

Generated outputs:

| Output | Content |
|--------|---------|
| `control_events.csv` | `0x80`/`0x81` control/MTU events. |
| `reconstructed_packets.csv` | One row per IPv4 packet with DTLS summary and PCAP match status. |
| `dtls_handshake_summary.csv` | Every DTLS record with type, epoch, sequence, PSK identity, alert. |
| `pcap_comparison.csv` | Frame-by-frame comparison between reconstruction and PCAP. |
| `missing_packets.csv` | Packets present in TSV reconstruction but absent/broken in PCAP. |
| `frame_times.csv` | Wall-clock timestamps from the btsnoop log. |
| `raw_att_*.bin` / `ip_stream_*.bin` | Raw byte streams. |
| `reconstructed.pcap` | A fresh PCAP rebuilt from the TSV reconstruction. |
| `timeline.txt` | Human-readable chronological narrative. |

## 7. Redaction and ethics

* All processing was performed locally on the supplied files.
* Encrypted application-data payloads are reported as opaque; no attempt was made
to brute-force keys.
* The captured PSK identities (`MD-...`) are device-specific values observed in
the handshake; they are included because they are necessary to describe the
protocol.  The secret keys themselves are **not** in the capture.
* The `BOOTSTRAP` PSK key bytes are not included here; they are already present
in the BlueBit repository source and are referenced only by file/identity.

---

*Report generated by `analyse_inspire3_capture.py` on the supplied Fitbit
Inspire 3 capture set.*
