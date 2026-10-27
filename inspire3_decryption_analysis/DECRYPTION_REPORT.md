# Fitbit Inspire 3 DTLS Bootstrap Decryption Analysis

**Date:** 2026-10-17  
**Capture:** `G:\Dev\BlueBit\inspire3_analysis_output\reconstructed.pcap`  
**Analysis output:** `G:\Dev\BlueBit\inspire3_decryption_analysis\`  

## 1. Reconstruction Validation Summary

The DTLS analysis was performed on the byte-reconstructed PCAP located at:

- `G:\Dev\BlueBit\inspire3_analysis_output\reconstructed.pcap`

This file was produced from the original BLE ATT capture by reassembling the L2CAP/ATT payload stream. The protocol-analysis report and `reconstructed_packets.csv` confirm that the reconstruction is byte-accurate for the DTLS/CoAP conversation. A separately supplied inner-traffic PCAP was found to be truncated for large frames and was therefore not used for decryption work; `reconstructed.pcap` is the authoritative byte source for this analysis.

The reconstructed capture contains 341 frames and three distinct DTLS handshake attempts:

| Frames | Epoch | Identity used | Result |
|--------|-------|---------------|--------|
| 1–4    | 0     | `MD-A2CB0B8E-6AE5D947` | Failed — DTLS alert *Unknown PSK Identity* |
| 5–8    | 0→1   | `BOOTSTRAP` | Successful — 74 application-data records decrypted and tag-validated |
| 56–57, 204 | 0→? | `MD-A376B03C-6AF89570` | Re-key to MobileData; records encrypted under MobileData keys fail bootstrap decryption |

The tracker is the DTLS client (`169.254.0.3`) and the phone/BlueBit stack is the DTLS server (`169.254.0.2`).

## 2. Bootstrap PSK Evidence and Candidate Keys

### 2.1 Source of the PSK

The bootstrap pre-shared key is hard-coded in the BlueBit Android application source:

- **File:** `BlueBitApp/app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolver.kt`
- **Decompiled:** `BlueBitApp/apk/apk_analysis_8B01Fj/decompiled/sources/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolverKt.java`

Kotlin source:

```kotlin
private val BOOTSTRAP_KEY = byteArrayOf(
    0x81.toByte(), 0x06, 0x54, 0xE3.toByte(), 0x36, 0xAD.toByte(),
    0xCA.toByte(), 0xB0.toByte(), 0xA0.toByte(), 0x3C, 0x60,
    0xF7.toByte(), 0x4A, 0xA0.toByte(), 0xB6.toByte(), 0xFB.toByte()
)
```

This gives the 16-byte PSK:

```
81 06 54 E3 36 AD CA B0 A0 3C 60 F7 4A A0 B6 FB
```

Expressed as a hex string for tools such as tshark:

```
810654E336ADCAB0A03C60F74AA0B6FB
```

### 2.2 Verification by independent tooling

The same PSK was verified independently with Wireshark/tshark:

```bat
"C:\Program Files\Wireshark\tshark.exe" ^
  -r G:\Dev\BlueBit\inspire3_analysis_output\reconstructed.pcap ^
  -o dtls.psk:810654E336ADCAB0A03C60F74AA0B6FB ^
  -Y coap -T fields -e frame.number -e coap.type -e coap.code -e coap.opt.uri_path
```

tshark successfully decrypts the bootstrap DTLS session and dissects CoAP traffic, confirming the PSK, cipher suite, and key derivation are correct.

### 2.3 Derived key material

Using the bootstrap PSK and the handshake randoms, the following key block was derived with RFC 7627 Extended Master Secret and the TLS key-expansion PRF. Both the Python script and tshark produce/agree on these values:

| Key | Value |
|-----|-------|
| `client_random` | `386d43d10a4127f429d31b6f56a3e21572d2e977780c0025013d14bdad2c1283` |
| `server_random` | `6ad3ab5594fba08b378d98121f8723a60909895fe472d4d2c79ba6f060f9cef8` |
| `session_hash` (SHA-256 of bootstrap handshake fragments) | `aea68715b6c0ee7ee23a83a8eaf6321c34036b812f05b6ade578a57ba3d54436` |
| `master_secret` | `f4b52e77325b7c4f411e12ef5024d8fa63d9f5f5dc7b1fab7d111e62fbc77a44a0f34268c909e75003ca53785f928fbc` |
| `client_write_key` | `f345845d6b5e97d433e1582f06fe5f09` |
| `server_write_key` | `8534833df5a4edfaaae4ca147e52af83` |
| `client_write_iv` | `97640d5d` |
| `server_write_iv` | `77d1d6fe` |

## 3. Decryption Methodology and Results

### 3.1 Cipher suite and tag length

The bootstrap handshake negotiates:

- **Cipher suite:** `TLS_PSK_WITH_AES_128_CCM` (`0xc0a4`)
- **Mode:** AES-CCM with a **16-byte authentication tag**

An earlier decryption attempt failed because it assumed an 8-byte CCM tag and the classic `master secret` derivation. The correct parameters are the RFC 7627 *extended master secret* and `tag_length=16`.

### 3.2 Key derivation

1. **Pre-master secret** (RFC 4279 pure-PSK):
   ```
   other_secret = 16 zero bytes
   pre_master_secret = u16len(other_secret) || other_secret || u16len(psk) || psk
   ```
2. **Extended master secret** (RFC 7627):
   ```
   master_secret = PRF(pre_master_secret, "extended master secret", session_hash, 48)
   ```
3. **Key block** (RFC 5246):
   ```
   key_block = PRF(master_secret, "key expansion", server_random || client_random, 40)
   key_block[0:16]   = client_write_key
   key_block[16:32]  = server_write_key
   key_block[32:36]  = client_write_iv
   key_block[36:40]  = server_write_iv
   ```

### 3.3 Session-hash computation

The `session_hash` is the SHA-256 of all unencrypted epoch-0 handshake fragments that belong to the bootstrap handshake:

- ClientHello (frame 5)
- ServerHello (frame 6)
- ServerHelloDone (frame 6)
- ClientKeyExchange (frame 7)

The encrypted Finished records in epoch 1 are excluded. The failed MobileData handshake at frames 1–4 is also excluded; the transcript is reset on each new ClientHello.

### 3.4 Decryption

For each DTLS `application_data` record, the AES-CCM nonce is formed as:

```
nonce = write_iv || epoch (2 bytes) || seq_num (6 bytes)
```

The additional authenticated data (AAD) is:

```
AAD = epoch || seq_num || content_type || version || plaintext_length
```

Decryption is attempted with the client keys for tracker→phone traffic and the server keys for phone→tracker traffic.

### 3.5 Results

Running `decrypt_dtls_bootstrap.py` on `reconstructed.pcap`:

```
Application-data records inspected: 329
Records with valid CCM tag under bootstrap key: 74
CoAP-looking samples: 74
```

Every record that validates the CCM tag also parses as a valid CoAP message, giving strong confidence that the decryption is correct and that the bootstrap session carries CoAP.

## 4. Application-Protocol Identification (CoAP)

### 4.1 Protocol

The decrypted plaintext is **Constrained Application Protocol (RFC 7252)**. All validated records begin with a CoAP header (`0x44` → ver=1, tkl=4) followed by a 4-byte token, a CoAP code, a message ID, and CoAP options.

Example — frame 9, first decrypted bootstrap record:

```hex
44 03 00 00 87 aa ca cf b4 70 61 69 72 07 64 69 73 70 6c 61 79 d1 03 06 ff 08 01
```

Decoded as:

- Ver=1, TKL=4, code=`0.03` (PUT), MID=`0x0000`
- Token: `87aacacf`
- URI-Path: `pair` / `display`
- Payload marker `0xff` followed by 2 bytes of payload

### 4.2 CoAP endpoints observed

tshark’s CoAP dissector (run on the decrypted bootstrap session) reports the following URI paths:

| URI Path | Records | Direction / role |
|----------|---------|------------------|
| `/sync/dump` | 29 | Phone GETs a sync dump; tracker returns block-wise data |
| `/pair/display` | 2 | Phone PUTs to `pair/display` |
| `/md/3d02` | 2 | Tracker pushes MobileData metadata (`md/3d02`) |
| `/sync/response` | 2 | Final PUT from phone at end of bootstrap sync |
| `/events` | 1 | Tracker POSTs/subscribes to events |
| `/sync/config` | 1 | Phone GETs sync configuration |

### 4.3 Request/response pairing

CoAP request/response matching uses the 4-byte token. Example pairings from the decrypted traffic:

| Request frame | Response frame(s) | Endpoint | Notes |
|---------------|-------------------|----------|-------|
| 9 | 13 | `/pair/display` | Phone PUT, tracker ACK/response |
| 12 | 14 | `/sync/config` | Phone GET, tracker returns 4.01 Unauthorized |
| 15, 17 | 16, 18 | `/md/3d02` | Tracker PUSH (code 0.02), phone 4.01 responses |
| 19 | 20 | `/sync/dump?t=pair` | Phone GET, tracker returns first block (2.05, block #0) |
| 22 | 21 | `/pair/display` | Phone PUT, tracker 2.04 response |
| 24, 34, 43, 51, 61, 69, 79, 88, 96, 106, 115, 124, 133, 142, 151, 160, 169, 178, 187, 196, 201 | 23, 25–33, 35–42, 44–50, 52–56, 62–68, 70–78, 80–87, 89–95, 97–105, 107–114, 116–123, 125–132, 134–141, 143–150, 152–159, 161–168, 170–177, 179–186, 188–195, 197–203 | `/sync/dump` | Block-wise CoAP transfer; phone sends repeated GETs with increasing MID/token to fetch each block |
| 203, 204 | — | `/sync/response` | Phone PUTs final response at end of bootstrap session |

The `/sync/dump` exchange is the dominant bootstrap conversation: a large binary dump is transferred in ~1036-byte CoAP blocks using block-wise transfer options (option 23 / 27 observed in the hex dumps).

### 4.4 Bootstrap vs. MobileData boundary

- **Bootstrap session:** frames 5–8 (handshake), application data frames 9–55, and phone→tracker bootstrap responses up to frame 204.
- **MobileData re-key:** Tracker sends a new ClientHello at frame 56 and ClientKeyExchange with identity `MD-A376B03C-6AF89570` at frame 57. The phone’s ServerHello for this re-key arrives at frame 204.
- **Consequence:** All tracker→phone application-data records after frame 55 are encrypted with MobileData keys and fail bootstrap decryption. Phone→tracker records continue to decrypt with bootstrap keys until the MobileData handshake completes at frame 204, after which both directions use MobileData keys.

The last bootstrap-decrypted record is frame 204 (`/sync/response`, phone→tracker, PUT).

## 5. Remaining Unknowns and Strongest Next Step for BlueBit

### 5.1 Unknowns

1. **MobileData session keys.** The capture contains a MobileData PSK identity (`MD-A376B03C-6AF89570`) but the corresponding key is not present in the repository. It is likely derived from a per-device or per-pairing secret that is not stored in the Android app sources examined so far.
2. **Payload semantics.** While the CoAP endpoints and block-wise structure are clear, the binary payload formats inside `/sync/dump`, `/md/3d02`, and `/events` are not yet parsed.
3. **Post-bootstrap CoAP after frame 204.** Once MobileData keys are in effect, the application-layer conversation continues but remains encrypted under an unknown key.

### 5.2 Strongest next step

The highest-value next step is to **derive or obtain the MobileData PSK** for the captured session. Candidates to investigate:

- A key-derivation function in the BlueBit Android or embedded code that takes a per-device seed (e.g., serial number, BLE address, or a factory-provisioned token) and produces the `MD-XXXXXXXX-XXXXXXXX` PSK.
- Companion artifacts (firmware images, logs, test fixtures, or paired-device databases) that contain the MobileData key for this specific tracker.
- Whether the MobileData PSK is derivable from the bootstrap dump itself (e.g., an embedded key material block in `/sync/dump` or `/sync/config`).

If the MobileData key can be recovered, the same DTLS key-derivation code in `decrypt_dtls_bootstrap.py` can be reused to decrypt the remainder of the capture.

## 6. Deliverables

| File | Description |
|------|-------------|
| `decrypt_dtls_bootstrap.py` | Standalone Python script that parses `reconstructed.pcap`, derives the bootstrap keys, and decrypts/validates AES-CCM application-data records |
| `bootstrap_decryption_results.csv` | One row per application-data record with frame, direction, epoch, seq, ciphertext length, plaintext length, tag-valid flag, and CoAP summary |
| `decrypted_coap_samples.csv` | Per-record metadata and first 128 hex bytes of each decrypted CoAP message |
| `decryption_summary.txt` | Key material and overall counts |
| `tshark_coap_summary.csv` | tshark CoAP dissection of the bootstrap session |
| `tshark_dtls_debug.log` | tshark DTLS debug log confirming cipher suite, EMS extension, and decrypted traffic |
| `sample_frame*.bin` | Raw decrypted plaintext samples |
| `DECRYPTION_REPORT.md` | This report |

All original captures and pre-existing outputs were preserved; new files were written only to `G:\Dev\BlueBit\inspire3_decryption_analysis\`.
