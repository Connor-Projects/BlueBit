# Reconstruction Validation Report

## Inputs processed

- `G:\Dev\BlueBit\inspire3_protocol_analysis_report.md`
- `G:\Dev\BlueBit\analyse_inspire3_capture.py`
- `G:\Dev\BlueBit\successful_sync_exchange.tsv`
- `G:\Dev\BlueBit\inspire3_inner_traffic.pcap`
- `G:\Dev\BlueBit\successful_sync.log`

## Generated outputs

All script outputs are under:

```
G:\\Dev\\BlueBit\\inspire3_decryption_analysis\\reconstruction_outputs\
```

Files produced by `python analyse_inspire3_capture.py --outdir G:\Dev\BlueBit\inspire3_decryption_analysis\reconstruction_outputs`:

| File | Purpose |
|------|---------|
| `reconstructed_packets.csv` | One row per reconstructed IPv4 packet with DTLS summary and PCAP status |
| `missing_packets.csv` | 11 packets present in TSV reconstruction but absent/broken in PCAP |
| `pcap_comparison.csv` | Frame-by-frame comparison between reconstruction and original PCAP |
| `dtls_handshake_summary.csv` | Every DTLS record with epoch, sequence, handshake type, PSK identity, alerts |
| `control_events.csv` | `0x80`/`0x81` link-control/MTU events |
| `frame_times.csv` | Wall-clock timestamps from `successful_sync.log` via tshark |
| `raw_att_phone_to_tracker.bin` / `raw_att_tracker_to_phone.bin` | Raw ATT data-PDU byte streams (prefix stripped) |
| `ip_stream_phone_to_tracker.bin` / `ip_stream_tracker_to_phone.bin` | Reassembled IPv4 byte streams |
| `reconstructed.pcap` | Fresh little-endian PCAP (DLT_RAW) rebuilt from the TSV |
| `timeline.txt` | Human-readable chronological narrative |

## Count verification

| Metric | Expected | Observed | Status |
|--------|----------|----------|--------|
| `reconstructed_packets.csv` total rows | 341 | 341 | OK |
| phone -> tracker packets | 178 | 178 | OK |
| tracker -> phone packets | 163 | 163 | OK |
| `missing_packets.csv` rows | 11 | 11 | OK |
| `pcap_comparison.csv` exact matches | 300 | 300 | OK |
| `pcap_comparison.csv` truncated/malformed | 30 | 30 | OK |

## ATT concatenation logic

The reconstruction is implemented in `analyse_inspire3_capture.py` in the function `reconstruct_ip_packets(pdus, direction_name)`.

### Per-PDU framing

Every ATT Write Command (`0x52` -> handle `0x0042`, phone -> tracker) and every ATT Notification (`0x1b` <- handle `0x0044`, tracker -> phone) carries a value whose first byte is a per-PDU control/sequence prefix:

- `0x00` – `0x7F`: data PDU. The remaining bytes are IPv4 payload fragments.
- `0x80`: empty link-control event.
- `0x81`: MTU advertisement; payload is `00000808` (big-endian 2056).

Zero-length data PDUs (prefix only, no payload) are treated as ACKs and do not advance the byte cursor.

### Reassembly algorithm

For each direction independently:

1. Filter out control PDUs (`prefix >= 0x80`).
2. Strip the 1-byte prefix from every data PDU.
3. Append the remaining payload bytes to a per-direction byte stream, preserving capture order.
4. Track, for every appended chunk, the source BLE frame number and the chunk length.
5. Walk the byte stream from offset 0 and slice out IPv4 packets using the 16-bit IPv4 total-length field at offset 2–3 of each IPv4 header (`data[pos+2:pos+4]` big-endian).
6. Advance the cursor by exactly that total-length and repeat until the stream is exhausted.

The script checks that each packet begins with the expected IPv4 version/IHL nibble `0x45`. If the remaining bytes are insufficient for the claimed total-length, reassembly stops. In this capture the reassembly produced 341 packets with **zero leftover bytes** in both directions, confirming the IPv4 total-length field is the only delimiter and the TSV is internally consistent.

### Why the PCAP differs

The original `inspire3_inner_traffic.pcap` was matched against the reconstruction by `(src, dst, ip_id)`. Matches were classified as:

- `exact`: byte-identical to reconstruction.
- `truncated`: PCAP frame is a strict prefix of the reconstructed packet.
- `missing`: no matching PCAP frame exists.

## Truncated / malformed frames

The 30 truncated frames are overwhelmingly large tracker -> phone application-data records:

| Direction | Count | Notes |
|-----------|-------|-------|
| tracker -> phone | 28 | 16 frames sliced to 77 bytes (IPv4 total-length claims 1101), 11 frames sliced to 78 bytes (claims 1102), and 1 frame sliced to 67 bytes (claims 579) |
| phone -> tracker | 2 | frames sliced to 91 bytes (claims 1115) and 176 bytes (claims 432) |

All but the three smallest truncated records are large tracker -> phone application-data frames with an IPv4 total-length of ~1101–1102 bytes, confirming the report's claim that the PCAP generator sliced them to 77–78 bytes.

## UDP datagram length verification

For a sample of 10 non-truncated reconstructed frames, the IPv4 total-length field equals the sum of the ATT payloads (after stripping the 1-byte prefix) that formed the packet:

| First BLE frame | Direction | IPv4 total-length | ATT payload sum | Match |
|-----------------|-----------|-------------------|-----------------|-------|
| 12167 | tracker -> phone | 129 | 129 | YES |
| 12174 | phone -> tracker | 159 | 159 | YES |
| 12184 | tracker -> phone | 150 | 150 | YES |
| 12186 | phone -> tracker | 43 | 43 | YES |
| 12221 | tracker -> phone | 129 | 129 | YES |
| 12228 | phone -> tracker | 159 | 159 | YES |
| 12241 | tracker -> phone | 139 | 139 | YES |
| 12247 | phone -> tracker | 103 | 103 | YES |
| 12253 | phone -> tracker | 92 | 92 | YES |
| 12260 | tracker -> phone | 87 | 87 | YES |

All sampled frames match exactly.

## Bootstrap DTLS parameters for decryption

The successful bootstrap handshake occurs after the first PSK identity `MD-A2CB0B8E-6AE5D947` is rejected with `unknown_psk_identity`.

| Parameter | Value |
|-----------|-------|
| ClientHello BLE frame | 12221 (tracker -> phone) |
| Client random | `386d43d10a4127f429d31b6f56a3e21572d2e977780c0025013d14bdad2c1283` |
| ServerHello BLE frame | 12228 (phone -> tracker) |
| Server random | `6ad3ab5594fba08b378d98121f8723a60909895fe472d4d2c79ba6f060f9cef8` |
| PSK identity | `BOOTSTRAP` |
| Cipher suite | `TLS_PSK_WITH_AES_128_CCM` (`0xC0A4`) |
| DTLS version | 1.2 (`0xfefd`) |

First application-data records after the bootstrap handshake:

| BLE frame | Direction | Epoch | Sequence |
|-----------|-----------|-------|----------|
| 12253 | phone -> tracker | 1 | 1 |
| 12260 | tracker -> phone | 1 | 1 |
| 12263 | phone -> tracker | 1 | 2 |
| 12267 | phone -> tracker | 1 | 3 |
| 12271 | tracker -> phone | 1 | 2 |
| 12279 | tracker -> phone | 1 | 3 |

## Conclusion

The TSV-based reconstruction is internally consistent and authoritative for this capture. The original PCAP is a useful cross-check but contains 30 truncated/malformed frames and is missing 11 reconstructed packets. The ATT concatenation logic (strip 1-byte prefix, concatenate per direction, slice on IPv4 total-length) is sufficient to recover all 341 IPv4/UDP/DTLS packets with no leftover bytes.
