# Fitbit Inspire 3 — Bluetooth HCI Traffic Capture Report

**Date:** 2026-10-10  
**Scope:** Determine whether an existing Bluetooth HCI snoop capture of the official Fitbit Android app talking to an Inspire 3 exists in `G:\Dev\BlueBit`, and if not, produce a precise manual capture procedure.  
**Constraint:** Read-only investigation. No BLE or CoAP traffic was sent to any watch during this work. No BlueBit source code was modified. No network requests were made.

---

## 1. Executive Summary

**No usable Bluetooth HCI snoop capture of the official Fitbit Android app communicating with an Inspire 3 was found in `G:\Dev\BlueBit`.**

The only capture-related artifact located is the archived Android bugreport `reports/other/fitbit_bugreport.zip`, which shows that Bluetooth HCI snoop logging was **enabled** (`bluetooth_hci_log value:1`), but the actual `btsnoop_hci.log` file is **not present** in the archive. All other files are text logs, markdown reports, or decompiled APK sources.

Because no capture exists, this report documents the exhaustive search performed and provides a precise, safe, step-by-step manual capture procedure that can be executed on the Android phone already paired with the Inspire 3.

**No source files were changed. No traffic was sent by BlueBit. No capture was obtained in this environment.**

---

## 2. Phase 1 — Existing Evidence Inspection

### 2.1 Files read

| File | Purpose |
|------|---------|
| `G:/Dev/BlueBit/BlueBitApp/fitbit_inspire3_evidence_audit.md` | Prior evidence audit establishing that no proven read-only CoAP GET exists for Inspire 3. |
| `G:/Dev/BlueBit/reports/INDEX.md` | Index of all investigation reports in the repository. |

### 2.2 Capture file search

A recursive search of `G:\Dev\BlueBit` was performed for:

- `btsnoop_hci.log` and `*.log` files that could be HCI captures
- `*.pcap`, `*.pcapng`, `*.cap`, `*.btsnoop`
- Wireshark/tshark exports
- Packet dump files
- CoAP/DTLS traffic extracts

**Result:**

| File type | Pattern | Files found |
|-----------|---------|-------------|
| `btsnoop_hci.log` | `**/btsnoop_hci.log` | **0** |
| `.pcap` | `**/*.pcap` | **0** |
| `.pcapng` | `**/*.pcapng` | **0** |
| `.cap` | `**/*.cap` | **0** |
| `.btsnoop` | `**/*.btsnoop` | **0** |
| Capture/dump/packet/snoop/wireshark/tshark filenames | broad name search | **0** actual capture files |

### 2.3 Text logs found (not HCI captures)

Several `.log` files exist but were verified as plain text, not binary HCI snoop captures:

| Path | Size | Type |
|------|------|------|
| `G:/Dev/BlueBit/BlueBitApp/.gradle/kotlin/errors/errors-*.log` | small | Kotlin build errors |
| `G:/Dev/BlueBit/BlueBitApp/.kotlin/errors/errors-*.log` | small | Kotlin build errors |
| `G:/Dev/BlueBit/BlueBitApp/mock-server/mock-server.log` | small | Mock server text log |
| `G:/Dev/BlueBit/Reports/fg_scan2_60s.log` | 161,169 bytes | ASCII text scan log |
| `G:/Dev/BlueBit/Reports/fg_scan3_60s.log` | 3,310,482 bytes | ASCII text scan log |
| `G:/Dev/BlueBit/Reports/fg_scan_60s.log` | 1,482,336 bytes | ASCII text scan log |
| `G:/Dev/BlueBit/Reports/fg_scan_loc_60s.log` | 10,766,559 bytes | ASCII text scan log |
| `G:/Dev/BlueBit/Reports/gatt_bonding_experiment.log` | 1,769 bytes | ASCII text experiment log |
| `G:/Dev/BlueBit/Reports/gatt_bonding_full.log` | 643,072 bytes | Unicode text experiment log |
| `G:/Dev/BlueBit/Reports/gatt_inspire3.log` | 5,342,010 bytes | Unicode text GATT log |
| `G:/Dev/BlueBit/Reports/gattcache_experiment.log` | 8,275 bytes | ASCII text experiment log |
| `G:/Dev/BlueBit/Reports/gattlink_experiment.log` | 95,142 bytes | Unicode text experiment log |
| `G:/Dev/BlueBit/Reports/goldengate_dsng_test.log` | 8,948 bytes | Unicode text experiment log |
| `G:/Dev/BlueBit/Reports/goldengate_experiment.log` | 854 bytes | ASCII text experiment log |
| `G:/Dev/BlueBit/Reports/normal_scan_fitbit.log` | 3,505,301 bytes | ASCII text scan log |
| `G:/Dev/BlueBit/tmp_hw/jadx_full.log` | small | Decompiler text log |

All of these are text logs from BlueBit experiments or build tools, not HCI snoop captures. The `file` command confirmed each as `ASCII text` or `Unicode text, UTF-8 text` with CRLF line terminators.

### 2.4 Other log/capture-like files

| Path | Size | Nature |
|------|------|--------|
| `G:/Dev/BlueBit/Reports/fitbit_logcat_capture.txt` | 171 bytes | Single-line Android logcat excerpt (`BluetoothGatt: onConnectionUpdated() ...`). Not an HCI capture. |

### 2.5 Bugreport archive inspection

The archived bugreport was inspected with `7z l` and `7z x -so`:

| Property | Value |
|----------|-------|
| Path | `G:/Dev/BlueBit/reports/other/fitbit_bugreport.zip` |
| Size | 25,415,756 bytes (≈25 MiB) |
| Contains `btsnoop_hci.log` | **No** |
| Contains any `.log` files | **No** |
| Contains Bluetooth-related files | Only `FS/vendor/etc/vintf/manifest/*bluetooth*.xml` |
| `bluetooth_hci_log` setting | **Enabled** (`_id:2040 name:bluetooth_hci_log pkg:com.android.shell value:1`) at `dumpstate-2026-10-03-00-55-13.txt:669490` |

The bugreport confirms the phone had HCI snoop logging turned on, but the actual capture file is missing from the archive. This is consistent with OEM behavior where the snoop log is sometimes omitted from bugreports or located in a path that bugreport does not collect.

### 2.6 Phase 1 conclusion

**No existing Bluetooth HCI snoop capture of the official Fitbit Android app talking to an Inspire 3 exists in `G:\Dev\BlueBit`.** The archived bugreport is the closest artifact and confirms the capture setting was enabled, but it does not contain the captured traffic.

---

## 3. Phase 2 — Controlled Capture Procedure

Because no capture exists, the following manual procedure should be used on the Android phone already paired with the Inspire 3 through the official Fitbit app.

### 3.1 Prerequisites

- Android phone with Developer options enabled.
- Official Fitbit Android app installed and already onboarded with the Inspire 3.
- Watch nearby and connected (or able to connect automatically when the app opens).
- `adb` available on the host PC, with USB debugging authorized on the phone.
- A clock/notebook to record exact start/end times.

### 3.2 Enable Bluetooth HCI snoop logging

#### Option A — Developer options UI

1. Open **Settings → System → Developer options**.
2. Find **"Bluetooth HCI snoop log"** (or **"Enable Bluetooth HCI snoop log"**).
3. Toggle it **ON**.
4. On some OEM skins the path is **Settings → Developer options → Bluetooth HCI snoop log**.
5. Reboot the phone if the OEM documentation or toggle text indicates it is required.

#### Option B — adb

```bash
adb shell settings put secure bluetooth_hci_log 1
```

Reboot if necessary:

```bash
adb reboot
```

### 3.3 Perform a normal, non-destructive read action

Choose exactly one simple read action. Do **not** pair, unpair, factory-reset, or change settings.

**Recommended action:**

1. Open the official Fitbit app.
2. Navigate to the **device / Inspire 3** tile or **Settings → Inspire 3**.
3. Pull down to refresh, or open a screen that displays device info/battery/status.
4. Wait for the status to load (typically 5–30 seconds).
5. Leave the screen open a few more seconds to ensure any background polling completes.

**Record:**

- Exact start time (UTC/local).
- Exact end time.
- Screen/action performed (e.g., "refreshed Inspire 3 device info screen").

### 3.4 Disable Bluetooth HCI snoop logging

#### Option A — UI

1. Return to **Settings → Developer options**.
2. Toggle **"Bluetooth HCI snoop log"** **OFF**.

#### Option B — adb

```bash
adb shell settings put secure bluetooth_hci_log 0
```

### 3.5 Retrieve the capture file

#### Primary path

```bash
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
```

#### Alternative paths (OEM-dependent)

```bash
adb pull /sdcard/btsnoop_hci.log
adb pull /data/misc/bluetooth/btsnoop_hci.log
adb pull /data/log/bt/btsnoop_hci.log
```

#### Fallback via bugreport (use when direct pull fails due to permissions)

```bash
adb bugreport fitbit_read_br
```

Then extract from the generated zip:

```bash
7z x fitbit_read_br.zip "FS/data/misc/bluetooth/logs/btsnoop_hci.log"
# or, depending on OEM:
7z x fitbit_read_br.zip "FS/data/misc/bluetooth/btsnoop_hci.log"
7z l fitbit_read_br.zip | findstr btsnoop
```

> **Note:** The bugreport method is not guaranteed to include the file; some devices omit it. If it is missing, try pulling directly as root (if the device is rooted) or use a file manager with root/system access to copy the file to `/sdcard/`.

### 3.6 Verify the capture file is valid

#### File magic check

A valid `btsnoop_hci.log` begins with the Btsnoop file magic:

```
62 74 73 6E 6F 6F 70 00
```

which is `"btsnoop\0"`.

Check with Python:

```python
with open('btsnoop_hci.log', 'rb') as f:
    magic = f.read(8)
    print(magic)
    assert magic == b'btsnoop\x00', 'Invalid btsnoop magic'
```

#### Wireshark / tshark

Open directly in **Wireshark** (`File → Open → btsnoop_hci.log`). Wireshark recognizes the format natively.

With tshark:

```bash
tshark -r btsnoop_hci.log
tshark -r btsnoop_hci.log -Y "btatt"
tshark -r btsnoop_hci.log -Y "btl2cap"
```

If Wireshark/tshark are not installed, the Btsnoop format can be parsed locally with Python; the file header is 16 bytes (magic + version + data link type), followed by 25-byte records (incl. 8-byte original length, 8-byte included length, 4-byte flags, 4-byte drops, 8-byte timestamp).

### 3.7 Expected contents of a good capture

After a successful device-info refresh, the file should contain:

- BLE advertisements and scan responses.
- LE connection setup, MTU exchange, and GATT service discovery.
- Writes to the Gattlink receive characteristic (`ABBAFF01-...`).
- Notifications from the Gattlink transmit characteristic (`ABBAFF02-...`).
- Gattlink framing bytes (reset, data, ack frames).
- DTLS records (handshake + application data).
- CoAP message headers if DTLS is decrypted or if any CoAP travels unencrypted.

---

## 4. Phase 3 — Capture Analysis

**Not applicable: no capture was found.**

If a capture is obtained later, it should be analyzed for the following, distinguishing observed bytes from deterministic parsing and hypotheses:

1. **BLE connection and service discovery** — connection handle, MTU, discovered services and characteristics.
2. **Gattlink traffic on `ABBAFF01` / `ABBAFF02`** — frame types, sequence numbers, reassembly.
3. **DTLS session establishment** — ClientHello, ServerHello, PSK identity, epoch/sequence numbers.
4. **CoAP requests/responses** — method, path, token, message ID, options, response code, payload presence.
5. **For each candidate read** record: path, method, request payload, response code, ordering, success, read-only semantics, direct observation vs. inference.

---

## 5. Phase 4 — Candidate Safety Gates

Because no capture exists, no candidate read can be evaluated against live traffic. The existing evidence audit already evaluated the known candidates from static analysis:

| Path | Method | Payload | Response / parser | Read-only | Inspire 3 success | Passes all gates |
|------|--------|---------|-------------------|-----------|-------------------|------------------|
| `/.well-known/core` | GET | none | 4.04 Not Found | ✅ | ❌ | **No** |
| `/alarms` | GET | none | `AirAlarmProtocol.parseDeviceAlarms()` | ✅ | ❌ (dead code) | **No** |
| `/device/info` | GET | none | none | ✅ in principle | ❌ | **No** |
| `/pair/status` | GET | none | none | ⚠️ | ❌ | **No** |
| `/pair/display` | GET | none | none | ❌ official app uses PUT | ❌ | **No** |
| `/bond/control` | GET | none | none | ❌ official app uses PUT | ❌ | **No** |
| `/sync/status` | GET | none | none | ✅ in principle | ❌ | **No** |
| `/sync/config` | GET | none | none | ✅ in principle | ❌ | **No** |
| `md/606` | GET | none | `DeviceInfo` protobuf | ✅ | ❌ inferred | **No** |
| `liveactivity` | GET | none | `LiveActivity` protobuf | ✅ | ❌ inferred | **No** |

**Result:** No candidate meets every required gate for the Inspire 3. The missing gate is almost always "successful use on physical Inspire 3" due to lack of captured traffic.

---

## 6. Phase 5 — Redaction

No derivative capture was produced, so no redaction was performed on capture data. The single text log inspected (`fitbit_logcat_capture.txt`) already had its MAC address masked (`XX:XX:XX:XX:5F:99`).

If a capture is produced later, redact before sharing:

- Bluetooth MAC addresses (phone and watch BD_ADDRs).
- Device name and serial number from Device Information service or advertisements.
- Advertisement manufacturer/service data containing identifiers.
- Account identifiers or user data in any decrypted protobuf.
- Timestamp/location metadata from bugreport archives.

---

## 7. Phase 6 — BlueBit Changes

**No BlueBit source code was modified.**

**No BLE or CoAP traffic was sent by BlueBit during this investigation.**

---

## 8. Report Contents Checklist

| Required item | Status |
|---------------|--------|
| 1. Existing captures found and inspected | ✅ Documented in §2 and §3. |
| 2. Capture acquisition result | ✅ No usable capture found. |
| 3. Exact capture filename, size and validity | ✅ No capture file exists; closest artifact is bugreport zip (25,415,756 bytes) without `btsnoop_hci.log`. |
| 4. Tools used | ✅ `7z`, `find`, `file`, `grep`, Python for inspection; Wireshark/tshark not locally installed. |
| 5. Observed BLE/Gattlink/DTLS/CoAP sequence | ✅ Not observed; prior static/dynamic evidence summarized from evidence audit. |
| 6. Each candidate path, method and response evidence | ✅ Summarized in §5; detailed in prior audit. |
| 7. Read-only safety assessment | ✅ No candidate passes all gates. |
| 8. Authentication/session limitations | ✅ MobileData PSK required for post-onboarding reads; bootstrap PSK read-only path not established. |
| 9. Redactions performed | ✅ None required (no derivative produced). |
| 10. Remaining uncertainties | ✅ Documented in §9. |
| 11. Whether any candidate meets every required gate | ✅ No. |
| 12. The single best next step | ✅ Documented in §10. |

---

## 9. Remaining Uncertainties

1. **Which CoAP resources does the Inspire 3 expose over the bootstrap PSK session?** `/.well-known/core` returns 4.04, so standard CoAP discovery does not apply.
2. **Does the official app perform a successful read-only CoAP GET before onboarding?** No traffic capture exists to confirm or deny this.
3. **What is the exact request/response for post-onboarding reads such as `md/606` or `liveactivity`?** Known only from APK static analysis, not live capture.
4. **Are `/pair/*`, `/bond/*`, or `/sync/*` GETs truly side-effect-free on Inspire 3 firmware?** Method alone does not guarantee read-only semantics.
5. **Can the MobileData PSK be obtained legitimately by BlueBit?** Without it, post-onboarding reads cannot be decrypted or exercised.
6. **Does the archived bugreport's missing `btsnoop_hci.log` indicate OEM omission, or was the file located elsewhere?** The bugreport contains no alternative Bluetooth log path.

---

## 10. Single Best Next Step

**Execute the manual capture procedure in §3 on the already-paired Android phone while refreshing the Inspire 3 device info screen in the official Fitbit app.**

After retrieval:

1. Verify the file with the Btsnoop magic check or Wireshark.
2. Locate the connection interval for the Inspire 3 MAC address.
3. Identify Gattlink frames, DTLS records, and CoAP headers.
4. Determine whether the official app sends a GET that returns `2.xx`, and capture the exact path, method, payload, and response structure.
5. Only after a safe, successful read path is observed on the physical Inspire 3 should BlueBit consider adding a corresponding, consent-gated diagnostic.

---

## 11. Explicit Statements

| Statement | Value |
|-----------|-------|
| BlueBit source files changed | **No** |
| BLE/CoAP traffic sent by BlueBit | **No** |
| Network requests made | **No** |
| Credentials extracted | **No** |
| Authentication bypass attempted | **No** |
| Usable capture obtained | **No** |
| Manual capture procedure provided | **Yes** |

---

*End of report.*
