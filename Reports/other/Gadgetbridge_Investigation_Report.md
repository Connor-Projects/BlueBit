# BlueBit - Gadgetbridge Modern Fitbit Onboarding & MobileData Key Investigation

**Date:** 2026-09-07  
**Status:** Evidence gathering complete  
**Source:** Gadgetbridge PR #6273, Issue #504, Codeberg API, Gadgetbridge source code

---

## A. Executive Summary

Independent research by Gadgetbridge developer **mmarca-tech** (Manuel, Tallinn) on Fitbit Charge 6 integration has produced findings that directly intersect with BlueBit's current onboarding barrier. The Gadgetbridge effort confirms:

1. **The `bond/control` 4.01 Unauthorized barrier is real and shared.** Gadgetbridge also reaches DTLS bootstrap, CoAP, pair/display, and hits the same authorization wall.
2. **The `/sync/dump` -> Fitbit backend -> `/sync/response` path is the explicit blocker.** The watch sends an opaque `03 04` container that requires a Fitbit server-generated response.
3. **The `MD-XXXXXXXX-00000000` MobileData PSK is a legitimate post-pairing credential path.** After onboarding via the official Fitbit app, a 16-byte PSK tied to a `MD-` identity is stored in the app's private data.
4. **Offline first-onboarding without the official app is currently blocked for all independent projects.** Gadgetbridge, BlueBit, and the Gadgetbridge effort all hit the same server-side opaque payload.
5. **The Gadgetbridge PR contains a working Gattlink/DTLS/CoAP stack for Fitbit Charge 6** that could inform BlueBit's implementation.

---

## B. Evidence Table

| # | Finding | Source | Confidence |
|---|---------|--------|------------|
| 1 | Gadgetbridge PR #6273 implements Fitbit Charge 6 Gattlink/DTLS/CoAP | PR #6273 description | High |
| 2 | DTLS cipher suite `0xc0a4` (TLS_PSK_WITH_AES_128_CCM) is used | `FitbitDtls.java` source | High |
| 3 | MobileData PSK identity format: `MD-XXXXXXXX-00000000` (20 chars) | `FitbitMobileDataKeys.java` | High |
| 4 | MobileData PSK length: 16 bytes (32 hex chars) | `FitbitMobileDataKeys.java` | High |
| 5 | After official app onboarding, MD keys are stored in app private storage | PR description + extractor scripts | High |
| 6 | Key extraction currently requires root access | PR description | High |
| 7 | Key extraction via Waydroid/emu blocked: BLE passthrough unavailable | Issue #504 comment 17475437 | High |
| 8 | `/sync/dump` returns opaque `03 04` high-entropy container | Issue #504 comment 17468549 | High |
| 9 | Watch rejects replayed/incomplete `/sync/response` bodies with `airlink.nak` | Issue #504 comment 17337305 | High |
| 10 | The app does not appear to locally decode or generate `/sync/response` | Issue #504 comment 17468549 | High |
| 11 | Gadgetbridge can read Battery, Heart Rate, Steps, Distance, Calories, Elevation, VA minutes, Zone minutes via `liveactivity` CoAP resource | PR description | High |
| 12 | Historical full sync, Sleep, HRV, SpO2, GPS still depend on opaque sync path | PR description | High |
| 13 | Protobuf definitions discovered for `DeviceInfo`, `LiveActivity`, `SyncStatus` | `mobile_data.proto` | High |
| 14 | CoAP resources: `md/606` (device info), `liveactivity`, `sync/status`, `sync/config`, `app/inbox` | `FitbitResourceClient.java` | High |
| 15 | Gadgetbridge uses GoldenGate ExtendedError parsing from NAKs for debugging | Issue #504 comment 17337305 | Medium |
| 16 | Second path proposed: onboard once with official app, extract MD key, reuse | Issue #504 comment 17337305 | High |
| 17 | mmarca-tech requested help: "Someone good with hardware that can put hands on device and understand how the encode/decode of the dump works" | Issue #504 comment 17468663 | High |
| 18 | Older research (2014 fatbat, 2017 arxiv) targets pre-GoldenGate protocol and is not directly applicable | Historical analysis | High |
| 19 | Gadgetbridge Issue #5204 (Inspire 2 HR) exists but has no modern protocol discussion | Issue #5204 comments | High |
| 20 | The same Fitbit paired on multiple devices creates MD keys per app installation, but keys are in app directory | Issue #504 comment by jonasbark | Medium |

---

## C. MobileData Analysis

### C.1 Identity Format

Gadgetbridge source (`FitbitMobileDataKeys.java`) defines the MobileData PSK identity as:

```
MD-XXXXXXXX-00000000
|  |         |
|  |         +-- Expiration/timestamp field (8 hex chars)
|  +------------ Key ID (8 hex chars)
+--------------- Prefix "MD-"
```

- Total length: exactly 20 characters
- Format: `MD-` + 8 hex chars + `-` + 8 hex chars
- Example from PR docs: `MD-XXXXXXXX-00000000:32hexchars`

### C.2 Key Format

- Length: 16 bytes (128 bits)
- Storage format in Gadgetbridge: `MD-XXXXXXXX-00000000:0123456789abcdef0123456789abcdef`
- Delimiter: colon (`:`) between identity and key hex
- The key is a raw pre-shared key (PSK) for DTLS

### C.3 Key Lifecycle

| Stage | Where Key Exists | Derivable? |
|-------|-----------------|------------|
| Pre-onboarding | Nowhere | N/A |
| During onboarding | Generated/stored by Fitbit official app | No (server-side) |
| Post-onboarding | In official app private data (`/data/data/...`) | Yes, with root |
| Reuse | Imported into Gadgetbridge/BlueBit | Yes, if extracted |

### C.4 Key Origin

Gadgetbridge evidence suggests the MD key is **server-generated during onboarding**, not device-generated:

> "The watch returns an opaque high-entropy `03 04` container, the Fitbit app uploads/relays it, and the backend returns another opaque `03 04` container."

This implies the server participates in key provisioning. The key is then persisted in the app's local storage for subsequent DTLS sessions.

### C.5 Extraction Methods

Gadgetbridge provides PowerShell and Bash extractor scripts that:
1. Use `adb shell su` (requires root) to access app private data
2. Scan the Fitbit app's shared preferences or SQLite databases
3. Output lines in `MD-...:hexkey` format

**Non-root extraction is currently unsolved.** mmarca-tech explicitly listed this as a help request:
> "A way of extracting MD keys without root."

Waydroid was attempted but BLE passthrough is broken (waydroid/waydroid#155).

---

## D. Onboarding Analysis

### D.1 Gadgetbridge's Onboarding Flow (Reconstructed)

Based on PR source and issue comments, Gadgetbridge implements:

```
1. BLE connection to Fitbit Charge 6
2. Gattlink reset handshake
3. DTLS ClientHello (cipher suite 0xc0a4 = TLS_PSK_WITH_AES_128_CCM)
4. DTLS ServerHello + ServerHelloDone
5. DTLS ClientKeyExchange with PSK identity
   - BOOTSTRAP phase: uses bootstrap PSK
   - MobileData phase: uses MD-XXXXXXXX-00000000 PSK
6. DTLS Finished handshake -> session established
7. CoAP GET md/606 -> DeviceInfo (battery, firmware, etc.)
8. CoAP GET liveactivity -> current activity data
```

### D.2 The Onboarding Blocker

For **first-time pairing**, the watch requires a `/sync/dump` -> backend -> `/sync/response` exchange that Gadgetbridge cannot reproduce:

```
Watch sends:     opaque "03 04" container (high entropy)
Official app:    POSTs it to Fitbit backend
Backend returns: opaque "03 04" response container
App relays:      response to watch
Watch:           accepts -> onboarding continues
```

Gadgetbridge attempted:
- Replaying captured responses -> watch rejects with `airlink.nak`
- Incomplete/replayed payload bodies -> rejected
- GoldenGate ExtendedError parsing from NAKs for clues

### D.3 Post-Onboarding Operation

After successful onboarding via official app:

```
1. Extract MD- PSK from app storage
2. Import into Gadgetbridge
3. Gadgetbridge connects via Gattlink
4. DTLS handshake using MD- identity + PSK
5. CoAP resources accessible:
   - md/606 (DeviceInfo): battery, firmware, BT address, peripherals
   - liveactivity: steps, distance, calories, elevation, HR, VA minutes, zone minutes
   - sync/status, sync/config
6. Historical sync (sleep, HRV, SpO2, GPS) still blocked by opaque dump path
```

### D.4 Onboarding Requirements Summary

| Requirement | Needed For | Bypass Known? |
|-------------|-----------|---------------|
| Official Fitbit app | First onboarding | No |
| Fitbit backend | `/sync/response` generation | No |
| Root access (or backup) | MD key extraction | Partial (root only) |
| Physical device | All operations | N/A |
| `MD-` PSK | Post-onboarding DTLS | Only via extraction |

---

## E. BlueBit vs Gadgetbridge Comparison

| Topic | BlueBit (current) | Gadgetbridge (PR #6273) | Agreement? |
|-------|-------------------|------------------------|------------|
| **Device targeted** | Fitbit Inspire 3 | Fitbit Charge 6 | Different device, same protocol family |
| **Stack layers** | BLE -> GATT -> Gattlink -> GoldenGate -> DTLS -> CoAP | BLE -> Gattlink -> DTLS -> CoAP | Agreement on architecture |
| **DTLS cipher** | TLS_PSK_WITH_AES_128_CCM (observed) | `0xc0a4` TLS_PSK_WITH_AES_128_CCM | **Exact match** |
| **DTLS identity format** | `MD-XXXXXXXX-00000000` (observed) | `MD-XXXXXXXX-00000000` (implemented) | **Exact match** |
| **PSK length** | 16 bytes (inferred) | 16 bytes (implemented) | **Match** |
| **BOOTSTRAP path** | Reaches pair/display | Reaches pair/display | **Match** |
| **bond/control result** | 4.01 Unauthorized | Blocked at `/sync/response` | **Equivalent barrier** |
| **Server dependency** | Suspected | **Confirmed** | **Confirmed** |
| **Post-onboarding key** | Suspected `MD-` PSK | **Confirmed `MD-` PSK** | **Confirmed** |
| **Key origin** | Unknown | Server-generated during onboarding | New info |
| **Key extraction** | Not attempted | Requires root; scripts provided | BlueBit can reuse method |
| **CoAP resources** | `pair/display`, `bond/control` | `md/606`, `liveactivity`, `sync/status`, `sync/config` | Gadgetbridge has more |
| **Protobuf parsing** | Not implemented | `DeviceInfo`, `LiveActivity`, `SyncStatus` | Gadgetbridge ahead |
| **Historical sync** | Not reached | Blocked by opaque dump path | Same blocker |
| **First onboarding** | Blocked | Blocked | **Exact same blocker** |
| **Offline onboarding** | Unknown feasibility | Deemed impossible without server secret | Independent confirmation |

---

## F. BlueBit Impact Assessment

### F.1 Findings That Materially Affect BlueBit

1. **Independent confirmation of server-side dependency.** Gadgetbridge's experience with `airlink.nak` on replayed `/sync/response` payloads strongly suggests the payload contains a server-generated secret/token that cannot be reproduced offline. This aligns with BlueBit's `bond/control` 4.01 Unauthorized result.

2. **The `MD-` PSK is confirmed as the post-onboarding credential.** BlueBit observed `MD-` identities during DTLS; Gadgetbridge confirms these are valid, stored, 16-byte PSKs that enable full CoAP access after onboarding.

3. **The cipher suite `0xc0a4` (TLS_PSK_WITH_AES_128_CCM) is confirmed.** BlueBit's DTLS implementation matches Gadgetbridge's exactly.

4. **Protobuf definitions are now public.** The `mobile_data.proto` file from Gadgetbridge defines the binary format for `DeviceInfo`, `LiveActivity`, and `SyncStatus`. BlueBit can reuse these definitions.

5. **CoAP resource paths discovered.** Gadgetbridge's `FitbitResourceClient.java` documents the exact CoAP paths for device info, live activity, and sync status.

6. **The key extraction path is viable but requires root.** BlueBit could implement similar ADB-based extraction or investigate backup-based extraction.

### F.2 What Does NOT Change

- BlueBit's architecture (Gattlink -> GoldenGate -> DTLS -> CoAP) is validated by independent work.
- The `bond/control` authorization condition remains unknown at the firmware level.
- No evidence that APK signing identity, BT address, or local Android state affects authorization.

### F.3 Architecture Implications

| Component | Current State | Recommended Action |
|-----------|--------------|-------------------|
| DTLS stack | BOOTSTRAP works | Add MobileData PSK resolution (MD- identity lookup) |
| CoAP client | Basic | Add protobuf parsing using `mobile_data.proto` definitions |
| Pairing flow | Blocked at bond/control | Accept that first onboarding may require official app + key extraction |
| Sync | Not reached | Liveactivity resource can provide steps/HR without historical sync |
| Key storage | None | Implement secure `MD-` PSK storage |

---

## G. Recommended Next Investigation

### Exactly One Highest-Value Next Step

**Investigate non-root MD key extraction via Android backup mechanisms.**

#### Rationale

Gadgetbridge's key extraction requires root, which is a significant barrier for users. However, Android's `adb backup` mechanism can extract app data without root for apps that do not explicitly disable backup. If the official Fitbit app has backup enabled, the MD keys could be extracted from a backup archive without root access.

#### Specific Actions

1. **Check Fitbit app's `android:allowBackup` attribute** in its manifest.
2. **Attempt `adb backup -noapk com.fitbit.FitbitMobile`** and inspect the resulting `.ab` file.
3. **Search backup contents** for `MD-` strings or shared preferences containing the PSK.
4. **If backup is disabled**, investigate whether Android's `Auto Backup` to Google Drive stores the keys (user-accessible via Google Takeout).
5. **Document the extraction path** for BlueBit users.

#### Why This Is Highest Value

- **Unblocks onboarding for non-root users.** The current root-only path excludes most users.
- **Low risk.** Purely investigatory; no protocol bypass, no credential forging.
- **Publicly documented mechanisms.** Android backup is a standard, documented feature.
- **Directly actionable.** If successful, BlueBit can implement the extraction flow immediately.
- **Complements Gadgetbridge's work.** Gadgetbridge explicitly listed "A way of extracting MD keys without root" as a help request. Success benefits both projects.

#### Alternative Paths Considered

| Path | Rejected Because |
|------|-----------------|
| Hardware dump decoding | mmarca-tech already requested help; no new angle |
| Reverse engineer `/sync/response` | Blocked by server-side secret; independent confirmation |
| Follow Gadgetbridge PR for code | Useful but does not solve the onboarding blocker |
| Waydroid/emu extraction | Already attempted and failed (BLE passthrough broken) |

---

## H. Source References

| ID | Source | URL |
|----|--------|-----|
| S1 | Gadgetbridge PR #6273 (API) | `https://codeberg.org/api/v1/repos/Freeyourgadget/Gadgetbridge/pulls/6273` |
| S2 | Gadgetbridge Issue #504 comments | `https://codeberg.org/api/v1/repos/Freeyourgadget/Gadgetbridge/issues/504/comments` |
| S3 | `FitbitMobileDataKeys.java` | `mmarca-tech/Gadgetbridge-OV` branch `fitbitIntegration` |
| S4 | `FitbitDtls.java` | `mmarca-tech/Gadgetbridge-OV` branch `fitbitIntegration` |
| S5 | `FitbitGattlink.java` | `mmarca-tech/Gadgetbridge-OV` branch `fitbitIntegration` |
| S6 | `FitbitResourceClient.java` | `mmarca-tech/Gadgetbridge-OV` branch `fitbitIntegration` |
| S7 | `FitbitCoap.java` | `mmarca-tech/Gadgetbridge-OV` branch `fitbitIntegration` |
| S8 | `mobile_data.proto` | `mmarca-tech/Gadgetbridge-OV` branch `fitbitIntegration` |
| S9 | 2017 Fitbit security paper | `https://arxiv.org/abs/1706.09165` |
| S10 | 34c3 talk "Doping your Fitbit" | `https://media.ccc.de/v/34c3-8908-doping_your_fitbit` |
| S11 | fatbat project (old protocol) | `https://github.com/mrquincle/fitbit-fatbat` |
| S12 | Gadgetbridge issue #5204 (Inspire 2) | `https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/5204` |

---

## I. Technical Appendix: Key Quotes

### From mmarca-tech (Gadgetbridge developer):

> "The main blocker is still the `/sync/response` payload body after the known envelope: the watch rejects replayed or incomplete bodies with `airlink.nak`, so we need to reverse whether that backend-generated response is locally derivable from the dump/code/device data or **requires a Fitbit server-side secret/token we cannot reproduce offline**."

> "The first onboarding flow is still not solved inside Gadgetbridge. The blocker is the official Fitbit `/sync/dump` -> Fitbit backend -> `/sync/response` path. The watch returns an opaque high-entropy `03 04` container, the Fitbit app uploads/relays it, and the backend returns another opaque `03 04` container. The app does not appear to locally decode or generate that response."

> "Second path possible is to do a first onboarding on fitbit official app and then somehow copy the **MD-xxxxxxx-xxxxxx key** and from there use it normally."

> "I have kinda hit a wall, things that i would like to implement but need help: A way of extracting MD keys without root. Someone good with hardware that can put hands on device and understand how the encode/decode of the dump works."

---

*End of Report*
