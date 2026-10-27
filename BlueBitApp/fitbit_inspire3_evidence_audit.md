# Fitbit Inspire 3 — Evidence Audit: Official App Read-Only CoAP Operations

**Date:** 2026-10-10
**Scope:** Determine whether existing local evidence shows the official Fitbit Android app performing a real, successful, read-only CoAP operation against a physical Inspire 3.
**Constraint:** Read-only audit. No BLE or CoAP traffic was sent to any watch during this analysis. No source code was modified.

---

## 1. Executive Conclusion

**Existing evidence does NOT establish that the official Fitbit Android app performs a successful, read-only CoAP GET against a physical Inspire 3 over the bootstrap PSK session.**

The only CoAP read operation actually exercised on an Inspire 3 in the local corpus is BlueBit's `GET /.well-known/core`, which returned **4.04 Not Found**. The official APK decompilation shows read-like CoAP resources (`md/606`, `liveactivity`, `sync/status`, `sync/config`), but these are **post-onboarding** operations that require the per-device MobileData PSK and are inferred from static analysis, not captured in traffic. The Fitbit Air notification bridge lists additional GET candidates (`/alarms`, `/device/info`, `/pair/status`, etc.), but they are either dead code, write-like under a GET label, or never observed on Inspire 3 firmware. New external reverse-engineering findings from June 2026 (Saksham Bedi's Fitbit Air traffic capture, AyushSagar16/fitbit-air, and paceaitian/fitbit-air-notification-bridge) strengthen the protocol model for Fitbit Air and confirm that useful data can be read via standard BLE services or the Google Health API without implementing the proprietary CoAP channel, but **none of them establish that the same read-only CoAP resources, parsers, or authorization behavior apply to the Inspire 3**. In the strongest lead the normal data channel remained encrypted, and the demonstrated CoAP operations are either state-changing PUTs or Air-specific observations.

Therefore, the audit concludes that **no conclusively safe, proven, read-only CoAP GET for the Inspire 3 is currently established** from existing files or from the June 2026 external findings.

---

## 2. Existing Evidence Summary

### 2.1 Direct observations on the physical Inspire 3

| Observation | Source | Significance |
|-------------|--------|--------------|
| GATT connection and service discovery succeed without bonding | `reports/other/inspire3-gatt-report.md` | Confirms BLE transport layer works; does not establish CoAP semantics. |
| `GET /.well-known/core` sent via BlueBit `CoapReadOnlyProbe` | `reports/coap/CoAP_ReadOnly_Probe_Implementation_Notes.md` | The only CoAP GET actually transmitted to the Inspire 3 in the corpus. |
| Response code `4.04 Not Found` for `/.well-known/core` | `reports/coap/coap_44_response_investigation.md` | Watch understood the CoAP request but does not expose that discovery resource (or does not authorize it under bootstrap PSK). |
| No other CoAP GET observed on Inspire 3 | All CoAP reports | No captured traffic, log, or bugreport shows a different read path succeeding. |

### 2.2 Evidence from the official Fitbit APK decompilation

| Finding | Source | Significance |
|---------|--------|--------------|
| `pair/display` is **PUT** with `SHOW_CODE`/`CLEAR_CODE` protobuf | `reports/apk/Fitbit_Whole_APK_Onboarding_Audit.md` §3.1, §8.1 | State-changing, not read-only. |
| `bond/control` is **PUT** with `operationTimeout` protobuf | `reports/apk/Fitbit_Whole_APK_Onboarding_Audit.md` §4.1, §8.1 | State-changing authorization command; BlueBit observed `4.01 Unauthorized`. |
| `md/606`, `liveactivity`, `sync/status`, `sync/config` are **GET** | `reports/apk/Fitbit_Whole_APK_Onboarding_Audit.md` §7.3, §8.1; `reports/apk/Fitbit_Pairing_Deep_Research.md` §4.2 | Read-only in method, but **after onboarding** and require MobileData PSK. |
| `/sync/dump` → backend → `/sync/response` relay is required for onboarding | `reports/apk/Fitbit_Pairing_Deep_Research.md` §3.4, §4.3; `reports/apk/Fitbit_Whole_APK_Onboarding_Audit.md` §11 | Blocks independent onboarding; post-onboarding reads cannot be reached without server secret or extracted MobileData PSK. |
| `authTracker()` returns `false`; authentication is DTLS-PSK, not Android SMP | `reports/apk/fitbit-connection-gatt-analysis.md`; `reports/other/inspire3-fitbit-auth-analysis.md` | Establishes that reads happen inside DTLS/CoAP, not via simple BLE GATT reads. |

### 2.3 Evidence from the Fitbit Air notification bridge reference project

All of the following is static source analysis of `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate`.

| Finding | Source | Significance |
|---------|--------|--------------|
| `READ_ONLY_PROBE_PATHS` list: `/.well-known/core`, `/alarms`, `/device/info`, `/pair/status`, `/pair/display`, `/bond/control`, `/sync/status`, `/sync/config` | `CoapActivity.kt:680-686` | A static probe list exists, but the function `runReadOnlyAuthorizationProbe()` has **no call site** in the audited source. |
| `probeGet()` helper sends `GET {path}` | `CoapActivity.kt:492-516` | Construction matches BlueBit's `OutgoingRequestBuilder(path, Method.GET)`. |
| `getAlarms()` parses `/alarms` response | `CoapActivity.kt:588-590`, `AirAlarmProtocol.kt` | Only called from `runAlarmAutoDeleteTest()`, which also has **no call site**. |
| `/settings` read is implemented as **PUT** with request body | `CoapActivity.kt:130`, `AirHapticPatternExecutor.kt:88`, `AirDirectSettingsProtocol.kt` | Not a true CoAP GET; method is write-capable. |
| `helloworld` GET is a local BSD-socket demo | `BsdCoapActivity.kt:128` | Never sent over BLE/DTLS to a watch. |
| No hard-coded resource tables in native GoldenGate source | `xp/`, `GoldenGateBindings/src/main/cpp/` | Resource support is firmware-side; cannot be derived from host source alone. |

### 2.4 Archived bugreport

`reports/other/fitbit_bugreport.zip` was inspected. It contains `dumpstate-2026-10-03-00-55-13.txt`, which shows that the Android `bluetooth_hci_log` setting was enabled (`value:1`). However, **the actual `btsnoop_hci.log` is not included in the zip**, and no captured CoAP/GoldenGate traffic is present. The existing `next_read_evidence_audit.md` also found no actionable Fitbit/CoAP/GoldenGate log lines in the main log. This archive therefore contributes **no direct evidence** of a successful official-app read.

---

## 3. Candidate Read Operations and Evidence Gates

For each candidate, the following gates are evaluated:

1. Exact path and method.
2. Request payload (usually none for GET).
3. Response model / parser.
4. Read-only semantics (no side effects).
5. Successful use on Inspire 3 or clearly identified compatible firmware.

| Path | Method | Payload | Parser | Read-only | Inspire 3 success | Verdict |
|------|--------|---------|--------|-----------|-------------------|---------|
| `/.well-known/core` | GET | none | informal truncation | ✅ | ❌ 4.04 observed | Already known to fail. |
| `/alarms` | GET | none | `AirAlarmProtocol.parseDeviceAlarms()` | ✅ | ❌ dead code, never observed | Parser exists but no execution evidence on Inspire 3. |
| `/device/info` | GET | none | ❌ none in source | ✅ in principle | ❌ list only | No parser, no execution. |
| `/pair/status` | GET | none | ❌ none in source | ⚠️ name implies state mgmt | ❌ list only | No parser; possible side effects. |
| `/pair/display` | GET | none | ❌ none in source | ❌ official app uses PUT | ❌ list only | GET semantics unverified; PUT changes UI. |
| `/bond/control` | GET | none | ❌ none in source | ❌ official app uses PUT | ❌ list only | High state-change risk. |
| `/sync/status` | GET | none | ❌ none in source | ✅ in principle | ❌ list only | Post-onboarding / MobileData PSK. |
| `/sync/config` | GET | none | ❌ none in source | ✅ in principle | ❌ list only | Post-onboarding / MobileData PSK. |
| `md/606` | GET | none | `DeviceInfo` protobuf (APK) | ✅ | ❌ inferred only | Post-onboarding / MobileData PSK. |
| `liveactivity` | GET | none | `LiveActivity` protobuf (APK) | ✅ | ❌ inferred only | Post-onboarding / MobileData PSK. |
| `/settings` (semantic read) | PUT | `0x0A 0x01 0x01` | `AirDirectSettingsProtocol.parseHapticsIntensity()` | ❌ uses PUT | ❌ Air-specific | Not read-only; not Inspire 3. |

**Result:** No candidate satisfies all five gates for the Inspire 3.

---

## 4. Observed Facts vs. Hypotheses

### Observed facts

- BlueBit sent `GET /.well-known/core` to an Inspire 3 and received `4.04 Not Found`.
- The official APK contains GET handlers for `md/606`, `liveactivity`, `sync/status`, and `sync/config`.
- These post-onboarding GET resources require the MobileData PSK (`MD-XXXXXXXX-00000000`).
- The reference Air bridge's `READ_ONLY_PROBE_PATHS` and `/alarms` parser are present in source but have no production call sites.
- The archived bugreport enabled HCI snoop but does not contain the resulting capture file.

### Hypotheses (not proven by existing evidence)

- The Inspire 3 exposes any of `/alarms`, `/device/info`, `/pair/status`, `/sync/status`, or `/sync/config` over the bootstrap PSK session.
- A GET to `/pair/display` or `/bond/control` is side-effect-free.
- The `DeviceInfo` or `LiveActivity` protobuf layouts for Inspire 3 match those decoded from the APK or Gadgetbridge.
- The official app ever performs a successful read-only CoAP GET before onboarding completes.

---

## 5. Remaining Gaps

1. **Captured read traffic.** No HCI snoop, bugreport, or packet capture shows the official Fitbit app performing a CoAP GET that returns `2.xx` on an Inspire 3.
2. **Bootstrap-PSK resource table.** It is unknown which CoAP resources the Inspire 3 exposes before server onboarding; `/.well-known/core` returning 4.04 suggests the standard discovery convention does not apply.
3. **Post-onboarding read evidence.** `md/606`, `liveactivity`, `sync/status`, and `sync/config` are documented only by static analysis; no live trace confirms path, response code, payload, or parser compatibility on Inspire 3.
4. **Side-effect analysis for pair/bond/sync paths.** The reference list includes paths whose names associate with stateful operations; GET side effects are unverified.
5. **MobileData PSK extraction.** Even if post-onboarding reads are documented, BlueBit lacks a proven method to obtain the per-device MobileData PSK without root access to official-app private storage.

---

## 6. Recommended Evidence-Gathering Method: Android Bluetooth HCI Snoop Log

Because existing source and log evidence is insufficient, the recommended next evidence source is a **Bluetooth HCI snoop log** captured while the official Fitbit app performs a normal, non-destructive read of device information on an already-onboarded Inspire 3.

### 6.1 Android developer setting that enables the capture

- Open **Settings → System → Developer options**.
- Enable **"Bluetooth HCI snoop log"** (sometimes labeled **"Enable Bluetooth HCI snoop log"**).
- On some OEM skins the path is **Settings → Developer options → Bluetooth HCI snoop log**.
- Alternatively, via adb:
  ```bash
  adb shell settings put secure bluetooth_hci_log 1
  ```
- A reboot may be required on some devices for the setting to take effect.

### 6.2 How to generate and retrieve the log

1. **Prepare the device:**
   - Use a phone where the official Fitbit app is already paired with the Inspire 3 (post-onboarding state).
   - Ensure the watch is nearby and connected.
2. **Enable HCI snoop log** as described above and reboot if necessary.
3. **Trigger a normal read:**
   - Open the official Fitbit app.
   - Navigate to a screen that refreshes device information (e.g., dashboard, battery/status tile, device settings).
   - This should cause the app to issue CoAP GET requests such as `md/606` or `liveactivity`.
4. **Disable the snoop log:**
   - Toggle **Bluetooth HCI snoop log** off, or run:
     ```bash
     adb shell settings put secure bluetooth_hci_log 0
     ```
5. **Retrieve the log.** The default location varies by OEM:
   ```bash
   adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
   # Alternative locations:
   # /sdcard/btsnoop_hci.log
   # /data/misc/bluetooth/btsnoop_hci.log
   # /data/log/bt/btsnoop_hci.log
   ```
   If direct `adb pull` fails due to permissions, retrieve it via a bugreport:
   ```bash
   adb bugreport fitbit_read_br
   # The btsnoop file is usually inside FS/data/misc/bluetooth/logs/
   ```
6. **Open in Wireshark:** `btsnoop_hci.log` uses the Btsnoop file format and can be opened directly with Wireshark or `tshark`.

### 6.3 What traffic can and cannot be decoded

**Can be decoded:**
- BLE advertisement and scan-response packets.
- GATT connection events, MTU negotiation, service discovery, characteristic reads/writes, notifications, and indications.
- Raw bytes written to `ABBAFF01` and notifications from `ABBAFF02` (Gattlink framing).
- Gattlink reset, data, and acknowledgement frames once the framing is parsed.
- DTLS record layer headers (content type, version, epoch, sequence number, length).
- CoAP message headers (ver, type, token length, code, message ID, token).
- CoAP options, including Uri-Path, Content-Format, and Accept.

**Cannot be decoded without secrets:**
- DTLS application data payload (the encrypted CoAP request/response bodies) because the PSK is not in the capture.
- MobileData PSK (`MD-XXXXXXXX-00000000`) derived during onboarding.
- Protobuf contents inside encrypted CoAP bodies.
- Fitbit backend HTTPS traffic (outside Bluetooth HCI).

**Useful inference even without decryption:**
- The exact CoAP path and method the official app sends for a read (visible in unencrypted CoAP headers before DTLS encryption, or reconstructible from characteristic write sequences if Gattlink framing is parsed).
- Response code class (2.xx vs 4.xx vs 5.xx) from CoAP headers.
- Message sizes and timing, which distinguish successful reads from errors.
- Whether a request payload is present.
- Comparison with BlueBit's own capture to identify divergences in Gattlink/DTLS setup.

### 6.4 Redaction before sharing

Before sharing the capture, remove or replace the following:

- **Bluetooth MAC addresses:** the phone and watch BD_ADDRs appear in HCI commands and events.
- **Device name and serial number:** may appear in GATT reads of the Device Information service (`0x180A`) or in advertisement data.
- **Advertisement manufacturer/service data:** may contain device identifiers.
- **Account identifiers:** not present in HCI, but any paired-phone name or user data in protobufs (if decrypted) must be redacted.
- **Location/timestamp metadata:** bugreport archives contain phone state; strip if not needed.

Redaction approach:
1. Open the capture in Wireshark.
2. Use **Edit → Preferences → Name Resolution** to disable MAC and address resolution.
3. Use **File → Export Specified Packets** or `tshark` with display filters to keep only the relevant connection interval.
4. Replace sensitive byte sequences with `00` or masked values using a hex editor or a script that operates on known offsets.
5. Do not share the raw bugreport archive publicly; share only the filtered, redacted `btsnoop_hci.log` fragment.

---

## 7. Exact Next Step

1. **Do not add a new default or automatic read-only CoAP path to BlueBit** on the basis of existing evidence or the June 2026 Fitbit Air reverse-engineering findings. Air evidence does not close the Inspire 3 compatibility gap.
2. **Capture an HCI snoop log** as described in §6 while the official Fitbit app refreshes device info on an already-onboarded Inspire 3.
3. **Analyze the capture** to identify the exact CoAP GET path(s), response code(s), and presence/absence of request payloads used by the official app for a safe read.
4. **Compare the official-app Gattlink/DTLS/CoAP sequence** with BlueBit's existing probe to confirm whether BlueBit reaches the same authorization boundary.
5. Only after the capture confirms a safe, successful read path should a corresponding, consent-gated diagnostic be added to BlueBit.

---

## 9. External reverse-engineering findings (June 2026)

In June 2026 several independent reverse-engineering efforts against Fitbit Air became public. They add useful protocol context but do **not** close the Inspire 3 read-only gap.

### 9.1 "Jailbreaking My Google Fitbit Air" (Saksham Bedi, 19 June 2026)

This is the strongest external lead to date. The author captured live Bluetooth traffic from a physical Fitbit Air, mapped the Golden Gate / Gattlink / DTLS / CoAP stack, decrypted a bootstrap session, and identified resources including `aspen/info`, `events`, `eventsSecure`, pairing resources, and sync.

| Resource | Path / method | Parser | Read-only | Inspire 3 success |
|---|---|---|---|---|
| Device/app information | `aspen/info` | Decrypted CoAP body observed | Likely read-only | Not established — Air only |
| Event stream | `events` | Decrypted CoAP body observed | Likely read-only | Not established — Air only |
| Secure event stream | `eventsSecure` | Decrypted CoAP body observed | Likely read-only | Not established — Air only |
| Pairing resources | Pairing-related paths/methods | Decrypted CoAP body observed | Mixed (pairing is stateful) | Not established — Air only |
| Sync | Sync-related paths/methods | Decrypted CoAP body observed | Read-like but session-bound | Not established — Air only |

**Significance:** The work proves the bootstrap DTLS session can be decrypted and that CoAP resources exist beyond `/.well-known/core`. However, the normal data channel remained encrypted, the investigation did not complete access to it, and — crucially — **the work was performed on Fitbit Air, not Inspire 3**. It therefore does not establish that any of these resources exist, are reachable, or are read-only on the Inspire 3.

### 9.2 AyushSagar16/fitbit-air

This open-source implementation reads live heart rate over the standard Bluetooth Heart Rate GATT service and obtains steps and history through the Google Health API.

| Resource | Path / method | Parser | Read-only | Inspire 3 success |
|---|---|---|---|---|
| Heart rate | Standard BLE HR service (`0x180D`) | Standard HR measurement parser | Yes | BLE HR service is generic, but no Inspire 3 test reported |
| Steps / history | Google Health API | Health Connect API | Yes | Not Inspire 3 specific; not CoAP |

**Significance:** It demonstrates that useful data can be accessed without implementing the proprietary CoAP channel. However, it does **not** establish a successful CoAP GET on the Inspire 3, nor does it exercise any Fitbit-specific CoAP resource.

### 9.3 paceaitian/fitbit-air-notification-bridge

Already referenced in §2.3. This local project establishes a bootstrap DTLS session and uses `PUT /settings` to obtain and modify haptic intensity, then restores it. The README states the notification path was validated on a physical Fitbit Air.

| Resource | Path / method | Parser | Read-only | Inspire 3 success |
|---|---|---|---|---|
| `/settings` | PUT with request body | `AirDirectSettingsProtocol.parseHapticsIntensity()` | No — state-changing PUT | Not established — Air only |

**Significance:** Useful protocol evidence for the DTLS/CoAP bootstrap flow, but the demonstrated operation is a state-changing PUT, not a read-only GET, and cannot be assumed compatible with the Inspire 3.

### 9.4 Why none of these findings close the Inspire 3 gap

- **Product mismatch:** All three leads target Fitbit Air, a different product with different firmware. The Inspire 3 may not expose the same CoAP resource table, may use different authorization rules, or may disable the bootstrap read paths observed on Air.
- **Normal data channel remains encrypted:** In the strongest lead, the decrypted session did not extend to the normal data channel, so no complete read-only CoAP operation on application data was demonstrated even on Air.
- **Demonstrated operations are not proven read-only CoAP GETs on Inspire 3:** The CoAP work is either a state-changing PUT (`/settings`), relies on standard BLE services or the Google Health API (AyushSagar16), or is observed on Air without Inspire 3 verification (Bedi).

**Conclusion from external findings:** No public report or repository found as of June 2026 proves the complete read-only CoAP operation required for the Inspire 3.

---

## 10. Source Citations

- BlueBit read-only probe and 4.04 observation:
  - `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt`
  - `reports/coap/CoAP_ReadOnly_Probe_Implementation_Notes.md`
  - `reports/coap/coap_44_response_investigation.md`
- Reference bridge read-only paths:
  - `fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt:680-686`
  - `fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt:492-516`
  - `fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/AirAlarmProtocol.kt`
- Official APK onboarding and post-onboarding reads:
  - `reports/apk/Fitbit_Whole_APK_Onboarding_Audit.md` §7, §8.1, §11
  - `reports/apk/Fitbit_Pairing_Deep_Research.md` §2.3, §4.2, §4.3
  - `reports/apk/fitbit-connection-gatt-analysis.md`
- Inspire 3 BLE/GATT baseline:
  - `reports/other/inspire3-gatt-report.md`
  - `reports/other/Inspire3_Gattlink_Startup_Investigation.md`
- Archived bugreport:
  - `reports/other/fitbit_bugreport.zip`
- June 2026 external reverse-engineering findings (summarized from user-supplied summaries; not independently browsed):
  - Saksham Bedi, "Jailbreaking My Google Fitbit Air", 19 June 2026.
  - `AyushSagar16/fitbit-air` (GitHub).
  - `paceaitian/fitbit-air-notification-bridge` (local reference at `G:/Dev/fitbit-air-notification-bridge`).

---

*End of audit.*
