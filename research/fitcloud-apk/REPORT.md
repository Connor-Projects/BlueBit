# FitCloudPro APK Reverse Engineering Research Report

**Date:** 2026-10-30
**Project:** `G:\Dev\BlueBit`
**Research directory:** `G:\Dev\BlueBit\research\fitcloud-apk`
**Target APK:** `com.topstep.fitcloudpro` (installed on Samsung Galaxy A17 4G, Android 14)

> **Verification status:** Version 2 of this report. Version 1 (initial draft) contained several
> incorrect conclusions that were corrected during a source-verification pass on 2026-10-30 —
> most importantly: **AES-256-CTR encryption exists** in the FitCloud BLE protocol (v1 wrongly
> concluded "XOR only, no AES"), and the wire packet format is the 8-byte-header
> `Fc#TransportPacket` (v1 mistakenly presented the Realtek audio stack's packet format as the
> FitCloud one). All claims below were re-derived from the decompiled sources with exact file and
> line references, and cross-checked against BlueBit's physical-hardware validation results
> (`Reports/FitCloud_P6_Validation_Report.md`).

---

## 1. Executive Summary

This report documents a static reverse-engineering investigation of the FitCloudPro Android APK
(package `com.topstep.fitcloudpro`). It covers the BLE architecture, UUID inventory,
authentication/cryptography, packet formats, device-specific implementation, and the Inspire 3
compatibility assessment.

**Key findings (all evidence-backed):**

- **BLE architecture:** FitCloudPro embeds **two separate BLE stacks**:
  1. **FitCloud tracker stack** (used for the P6): FitCloud SDK v2 → `Fc#Connector` (`p150kd.j`)
     → `Fc#TransportPacket` (`U1.e`) → **RxAndroidBle** → Android `BluetoothGatt`.
  2. **Realtek BBPro audio stack** (`com.realsil.sdk.bbpro`) for earbuds/audio products (BeePro),
     including a classic-Bluetooth SPP path. Its constants (`Mmi`, `StatusIndex`) are reused by
     the FitCloud stack as byte-value constant tables, which was a source of confusion in v1.
- **UUID inventory:** FitCloud devices use a Topstep 128-bit UUID base
  `XXXX????-3c17-d293-8e48-14fe2e4da212`. The connector default is service
  `000001ff-3c17-d293-8e48-14fe2e4da212` with write char `0000ff02-…-00805f9b34fb` and
  notify char `0000ff03-…-00805f9b34fb` — **exactly matching the physical P6's GATT**. The
  advertised `00003802-…` service is handled by a separate scan/characteristic-detection path
  (`Nd.b`) that expects char `00004a02` inside a `0x3802` service. That service is
  advertisement-only on the P6 (confirmed by hardware dump).
- **Authentication:** LOG in / BIND two-step sequence built from `FcProtocolPacket(cmdId, keyId, data)`
  frames. BIND sends request packet `(cmdId=0x03, keyId=0x12)` plus a key-material packet
  `(cmdId=0x03, keyId=0x11, 32-byte userId block)`. LOGIN sends `(0x03, 0x14)` plus
  `(0x03, 0x13, 32-byte userId block)`. A session token (base-36 millisecond timestamp) is stored
  under `token2_<MAC>` in SharedPreferences `fc_internal_storage`.
- **Encryption:** **AES-256-CTR is used on selected transport packets** (`Aes256CtrUtil`,
  `p334x9.b.o()`). The key is **statically derived** at connect time from public inputs —
  `userId` bytes ⊕ device MAC ⊕ a fixed 8-byte constant — with no key exchange. The key packets
  themselves are sent **unencrypted** (plaintext on-air). This makes the encryption
  obfuscation-grade against passive sniffers who lack the userId, but provides no real key
  secrecy, no forward secrecy, and no replay protection beyond a 16-bit packet sequence number.
- **Inspire 3 compatibility: ABSENT.** Zero references to "Inspire", "Fitbit", Fitbit Gattlink
  UUIDs, DTLS/GoldenGate/CoAP, or any Fitbit protocol logic in the entire decompiled app.
- **FitCloudPro explains nothing about BlueBit's Inspire 3 blocker** — it is a completely
  different protocol ecosystem (Topstep/Realtek, plaintext-derivable AES-CTR) from Fitbit's
  (Gattlink/GoldenGate/DTLS, certificate-based).

---

## 2. APK Metadata and Hashes

| APK File | SHA-256 | Size |
|----------|---------|------|
| `base.apk` | `8DA8D3AAD8045E45C9F9B80352AA37382C156EE55A242012A50EFFC78BBAA97C` | 111,775,345 bytes |
| `split_config.arm64_v8a.apk` | `C35D3D5E14661A27680114A0B00A5BAD573B86A23539473E408E1DC37F69A9C4` | 77,737,059 bytes |
| `split_config.en.apk` | `2C2FD80356A1D555BC292F86D2E38CCFBBE72EE766F38DCC17B6F2314F5705A9` | 156,057 bytes |
| `split_config.xxhdpi.apk` | `840AD0A56311AA141B5D9524CC7A07C42B74AA166AF3A53C47D41176C56D18E8` | 183,605 bytes |

Retrieved via `adb pull` from package `/data/app/…/com.topstep.fitcloudpro-…/`. Original APKs are
preserved unmodified in `research/fitcloud-apk/original/`.

---

## 3. Decompilation Details

| Tool | Version | Output | Notes |
|------|---------|--------|-------|
| JADX | 1.5.6 (`G:\Dev\BlueBit\Tools\jadx-1.5.6`) | `research/fitcloud-apk/decompiled/jadx/` | Java/Kotlin decompilation; ~170 decompile warnings (ProGuard/R8-obfuscated classes such as `p150kd.j`, `U1.e`, `p026be.f`) |
| Apktool | 2.11.0 (`G:\Dev\BlueBit\Tools\apktool.jar`) | `research/fitcloud-apk/decompiled/apktool/` (+ `apktool_arm64`, `apktool_en`, `apktool_xxhdpi`) | Resources, `AndroidManifest.xml`, manifest metadata, native libs, smali |

**Obfuscation:** R8 applied — most project classes shorted to 1-letter names, which preserved the
package hierarchy (`com.topstep.fitcloudpro.*`, `com.topstep.fitcloud.sdk.v2.*`,
`com.realsil.sdk.bbpro.*`) but renamed internals (`p150kd.j`, `U1.e`…). Runtime log tags survived
obfuscation (`Fc#Connector`, `Fc#AuthOperation`, `Fc#TransportPacket`, `Fc#DfuManager`,
`Aes256CtrUtil`), allowing class identification via the SDK's own logging; the BlueBit hardware
validation logs use the same tags (`Fc#Connector` / `Fc#AuthOperation`), confirming these classes'
roles.

**Manifest notes:** package `com.topstep.fitcloudpro`; queries include Google Health Connect
(`com.google.android.apps.healthdata`), social apps; `requiredSplitTypes="base__abi,base__density"`.

---

## 4. BLE Architecture

### 4.1 Two stacks, one app

```
┌──────────────────────────────────────────────────────────────────────┐
│ FitCloud Tracker Stack  (this is what talks to the P6)               │
│                                                                      │
│  FitCloud UI / features (Sport, Dial, Msg, Data, Dfu…(features))     │
│        ↓  (cmd listeners keyed by (cmdId, keyId))                    │
│  FitCloud SDK v2 public API  (com.topstep.fitcloud.sdk.v2.*)         │
│        ↓                                                             │
│  Fc#Connector  (p150kd.j)  — RxAndroidBle client, auth, config       │
│        ↓                                                             │
│  Fc#TransportPacket (U1.e) — 0xAA framing, CRC16, SN, AES-256-CTR    │
│        ↓             FcProtocolPacket (p349yd.a = cmdId|keyId|data)  │
│  RxAndroidBle (RxBluetoothGatt) → Android BluetoothGatt              │
│                                                                      │
│  GATT targets:                                                       │
│   · service 000001ff-3c17-d293-8e48-14fe2e4da212                     │
│       write char 0000ff02-0000-1000-8000-00805f9b34fb                │
│       notify char 0000ff03-0000-1000-8000-00805f9b34fb               │
│       (p150kd/j.java:155–161, log prefix "Fc#")                      │
│   · optional alt path (Nd.b): service 00003802-0000-1000-8000-…      │
│       with characteristic 00004a02-0000-1000-8000-…  (Nd/e.java)     │
└──────────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────────┐
│ Realtek BBPro Audio Stack (BeePro earbuds / audio devices)           │
│                                                                      │
│  com.realsil.sdk.bbpro.core.transportlayer.TransportLayer(.Packet)   │
│  com.realsil.sdk.bbpro.core.gatt.GattLayer / GattTransportLayer      │
│  com.realsil.sdk.core.bluetooth.connection.le.* (RFCOMM/SPP too)     │
│  Default BBPro GATT profile params (GattTransportConnParams):        │
│   service 000002fd-3c17-d293-8e48-14fe2e4da212                       │
│   chars    0000fd03 / 0000fd04 (0000-1000-8000-00805F9B34FB base)    │
│  NOTE: BBPro "TransportLayerPacket" (0xAA + seq + len) is the AUDIO  │
│  stack's frame — not the FitCloud tracker frame.  (v1 conflated it.) │
└──────────────────────────────────────────────────────────────────────┘
```

**Constant-table reuse (source of v1 confusion):** the FitCloud code, being decompiled from the
same app, imports Realsil constant tables as literal byte values — e.g. `StatusIndex.ANC_MODE`
(= `0xAA`, value −85 signed) is used as the FitCloud transport **magic byte**, and several
`Mmi` / `StatusIndex` constants supply key-derivation bytes. These are just constants; the BBPro
transport itself does not carry FitCloud tracker traffic.

### 4.2 Core classes (FitCloud tracker stack)

| Component | Class | Location | Role |
|-----------|-------|----------|------|
| Connector | `p150kd.j` | `sources/p150kd/j.java` | Owns RxAndroidBle connect, GATT config, `AuthOperation`, session keys, SP `fc_internal_storage` |
| Connector config | `B7.e` | `sources/B7/e.java` | GATT UUID holder (`f691g` service, `f685a` write char, `f692h` notify char), log prefix (`f693i = "Fc#"`) |
| Auth operation | `p026be.f` | `sources/p026be/f.java` | BIND/LOGIN sequencing, token (`token2_<mac>`) |
| Auth mode enum | `p181me.a` | `sources/p181me/a.java` | `BIND` / `LOGIN` / `AUTO` |
| Connection state | `p181me.d` | `sources/p181me/d.java` | DISCONNECTED → CONNECTING → CONNECTED (→ …) |
| Transport packet | `U1.e` | `sources/U1/e.java` | Header parsing/encoding, CRC16, SN, AES-CTR toggle (tag `Fc#TransportPacket`) |
| Protocol packet | `p349yd.a` | `sources/p349yd/a.java` | `FcProtocolPacket(cmdId, keyId, keyData)` |
| ACK result | `p349yd.b` | `sources/p349yd/b.java` | ACK success flag + SN |
| Key derivation / AES-CTR util | `p334x9.b` | `sources/p334x9/b.java` | `o()` = `Aes256CtrUtil`; static key (`f54121f`) & IV (`f54120e`) |
| OTA manager | `Fd.j` | `sources/Fd/j.java` | tag `Fc#DfuManager` (firmware/UI/resource updates) |
| Voice/AIGC channel | `Kd.e` | `sources/Kd/e.java` | AliAgentSdk data channel on `01ff` service chars `ff04`/`ff05` |
| File/asset transfer | `Fd.F`, `p166ld.d`, `p039ce.a`, `Hd.C0490a` | respective files | `File` → GATT path on service `00009966-3c17-…` chars `ffb1`/`ffb2` |
| 0x3802 detector | `Nd.e` / `Nd.b` | `sources/Nd/e.java`, `sources/Nd/b.java` | Advertised-service handling: expects char `00004a02` inside a `00003802` service |

### 4.3 BLE connection lifecycle (verified in `p150kd/j.java`)

1. **Connect:** `Fc#Connector.connect(address, userId, bindOrLogin, sex, age, height, weight)`
   (method `j.e()`, line 271). RxAndroidBle resolves the address:
   `rVar.f43827b.a(str)` → device object (line 365). Log line:
   `connect address0:%s userId:%s bindOrLogin:%b sex:%b age:%d height:%f weight:%f` (line 281).
2. **GATT config (default):** service `000001ff-3c17-d293-8e48-14fe2e4da212`, write char
   `0000ff02`, notify char `0000ff03` are registered into the connection config (`B7.e`, lines
   155–161). The write/notify roles come from `eVar2.f685a` (notify-capable `ff03` is stored in
   `f692h`, used for notifications; `ff02` in `f685a` for writes).
3. **Alternative detection:** `Nd.d` (wired at `p150kd/j.java:199`) checks discovered services for
   `00003802` (`Nd.b.apply` case 1, `x.a(e.f9242e)`) and then grabs characteristic `00004a02`
   inside it (`Nd/b.java:40–47`), throwing `p147k9.c(uuid)` when missing.
4. **Auth:** per §6 — `Fc#AuthOperation` (tag in logs, `p026be.f`) sends request + key packets.
5. **Session keys:** derived at connect (§7); cleared on disconnect (`p150kd/j.java:256–257`).
6. **Time-change receiver:** connector registers `IntentFilter(TIME_SET | DATE_CHANGED |
   TIMEZONE_CHANGED)` and pushes time to the device (`p150kd/j.java:213–217`).
7. **(Re)connection config:** `w` config object with `f18066a=true` (auto-reconnect) added to the
   connection mount queue (lines 186–208).

### 4.4 Reconnection / retry

- Auto-reconnect enabled by default (`w.f18066a = true`, `p150kd/j.java:188–190`).
- Disconnect clears `p334x9.b.f54120e / f54121f` (session keys) — reconnection re-derives them.
- `Fc#Connector` logs `setIsOtaIng:` and treats OTA as a distinct state on the connection holder.

---

## 5. UUID Inventory

UUID notation: `XXXX` rows = short UUIDs expanded on the standard Bluetooth base
`0000XXXX-0000-1000-8000-00805F9B34FB` (constructor `C9.b.b(int)`, `sources/C9/b.java:66`).

### 5.1 FitCloud device services / characteristics (topstep base `-3c17-d293-8e48-14fe2e4da212`)

| UUID | Kind | Role | Evidence | On P6 hardware dump |
|------|------|------|----------|---------------------|
| `000001ff-3c17-…` | Service | **Primary transport** (connector default) | `p150kd/j.java:155`, also `Kd/e.java:44` | ✅ Present (char set ff02/ff03) |
| `0000ff02-0000-1000-…` | Char | Write (command channel) | `p150kd/j.java:156,158` | ✅ WRITE |
| `0000ff03-0000-1000-…` | Char | Notify/write-no-resp (status channel) | `p150kd/j.java:157,159` | ✅ READ, WRITE_NO_RESPONSE, NOTIFY + CCCD |
| `0000ff04-0000-1000-…` | Char | Voice/AIGC data channel (AliAgentSdk) | `Kd/e.java:50–52` | ❌ Absent |
| `0000ff05-0000-1000-…` | Char | Voice/AIGC data channel | `Kd/e.java:47–49` | ❌ Absent |
| `00003802-0000-1000-…` | Service (advertised) | Device-family discriminator | `Nd/e.java:30–32` | ⚠️ Advertised **only** — not in connected GATT |
| `00004a02-0000-1000-…` | Char | Char expected inside a real `0x3802` service group | `Nd/e.java:33–35`, `Nd/b.java:40–47` | ❌ Absent (path unused on P6) |
| `0000d0ff-3c17-…` | Service | Config / feature-flag characteristics | `p118ia/k.java:9`, `p132ja/g.java:163–172`, `p279ta/b.java:352` | ✅ Present (ffd1…fff3, ffe0) |
| `00006287-3c17-…` | Service | Dial / watch-face service | `p118ia/f.java:9` | ✅ Present |
| `00006387-3c17-…` | Char | Dial write (write-no-resp) | `p118ia/f.java:12` | ✅ WRITE_NO_RESPONSE |
| `00006487-3c17-…` | Char | Dial status (write + notify + CCCD) | `p118ia/f.java:15` | ✅ WRITE, NOTIFY |
| `00006587-3c17-…` | Char | (device-variant channel; role not determined) | `p206oa/d.java:10` | ❌ Absent |
| `00009966-3c17-…` | Service | File/asset transfer service | `Fd/F.java:35`, `p166ld/d.java:33`, `p039ce/a.java:15`, `Hd/C0490a.java:13` | ❌ Absent |
| `0000ffb1-0000-1000-…` | Char | File-transfer write | `p166ld/d.java:36–38`, `Fd/F.java:36` | ❌ Absent |
| `0000ffb2-0000-1000-…` | Char | File-transfer notify | `p166ld/d.java:39–41`, `Fd/F.java:37` | ❌ Absent |
| `0000aa55-3c17-…` | Service | (secondary channel; role not determined) | `Hd/C0497h.java:38–39` | ❌ Absent |
| `020002fd-3c17-…` | Service (scan-filter literal) | Scan-filter acceptance list entry | `na/a.java:60`, `p178ma/c.java:139`, `p222pa/b.java:166` | ❌ Absent |
| `000002fd-3c17-…` | Service | BBPro audio-stack GATT profile default | `com/realsil/sdk/bbpro/core/gatt/GattTransportConnParams.java:11` | ❌ Absent (audio profile) |

### 5.2 Realtek BBPro audio-stack UUIDs (earbuds only, not the P6 path)

| UUID | Role | Evidence |
|------|------|----------|
| `0000ff12-0000-1000-…` (svc + ff13/ff14/ff15 chars) | Audio-device GATT transport | `GattLayer.java:15–17` usage; **also present as service [1] in the P6 dump** (unmapped on P6 — see §11) |
| `6A24EEAB-4B65-4693-986B-3C26C352264F` | Realtek vendor SPP | `D9/c.java:23`, audio path |
| `00001101-0000-1000-…` | Standard SPP | `D9/c.java:22` |
| `0000180F` / `00002A19` | Battery (audio side) | `p118ia/c.java` |
| `0000180A` (+ `00002A29`, `00002A23`, `00002A50`) | Device Information (audio side) | `p118ia/g.java` |

### 5.3 Preview / camera-streaming service (Nordic UART compatible)

| UUID | Role | Evidence |
|------|------|----------|
| `6e400001-b5a3-f393-e0a9-e50e25dcca8d` | Preview service | `com/topstep/fitcloud/sdk/v2/features/builtin/preview/PreviewFeatureImpl.java:58–60` |
| `6e400002-b5a3-f393-e0a9-e50e25dcca8d` | TX (write) | same, lines 64–66 |
| `6e400003-b5a3-f393-e0a9-e50e25dcca8d` | RX (notify) | same, lines 61–63 |

### 5.4 Realsil constant-table values reused by the FitCloud stack (not BLE UUIDs)

| Constant | Value | Used by FitCloud code as |
|----------|-------|--------------------------|
| `StatusIndex.ANC_MODE` | `0xAA` (−85) | Transport packet magic byte (`U1.e` header) |
| `StatusIndex.AVP_MULTI_LINK_STATE` | `0xA5` (−91) | Key-derivation constant byte (`p150kd/j.java:394`) |
| `Mmi.AU_MMI_DEV_POWER_ON_BUTTON_PRESS/RELEASE` | `0x54` / `0x55` | Key-derivation constants (same array) |
| `Mmi.AU_MMI_DEV_FACTORY_RESET_BY_SPP` | `0x5A` | Key-derivation constant (same array) |
| `Mmi.AU_MMI_AIRPLANE_MODE` | `0xD4` | IV constant (IV[0], `p150kd/j.java:400`) |
| `Mmi.AU_MMI_START_ROLESWAP` | `0xF0` | IV constants (IV[2], IV[6]) |
| `Mmi.AU_MMI_LIGHT_SENSOR_ON_OFF` | `0xD3` | IV constant (IV[3]) |
| `Mmi.AU_MMI_DISC_LINK_AND_ENTER_PAIRING_MODE` | `0x50` | keyId of one connector boot command (`p150kd/j.java:207`) |

### 5.5 FitCloud Company ID (manufacturer data)

| ID | Notes |
|----|-------|
| `0x5A54` (23124) | Vendor-specific Topstep/FitCloud identifier observed in the P6 advertisement; accepted by the SDK's scanner (`FcScanner` per `Reports/FitCloud_SDK_Compatibility_Report.md`, which documents the accepted set `0x5448`, `0x4254`, `0x5A54`) |

### 5.6 Absence of Fitbit UUIDs (Inspire 3)

| UUID family | Fitbit role | In APK |
|-------------|-------------|--------|
| `ADABFB00-6E7D-4601-BDA2-BFFAA68956BA` | Fitbit Gattlink service | ❌ |
| `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` (+ `ABBAFF01/02`) | Fitbit Gattlink service / chars | ❌ |
| `0000FD62` / `0000FD63` | Fitbit modern Gattlink services | ❌ |
| `AC2F0045` / `AC2F0145` | Fitbit control/ephemeral chars | ❌ |
| any literal "Inspire" / "Fitbit" string | — | ❌ (0 matches) |

---

## 6. Authentication Flow

**Classes:** `p026be.f` (`Fc#AuthOperation`), `p181me.a` (BIND/LOGIN/AUTO), `p150kd.j` (`Fc#Connector`).

### 6.1 FcProtocolPacket (transport payload)

`p349yd.a` = `{cmdId: byte, keyId: byte, keyData: byte[]}` — decompiled `toString()` confirms the
named vocabulary `FcProtocolPacket(cmdId=%#x, keyId=%#x, keyData=[…hex…])`. Feature/protocol
handlers subscribe to specific `(cmdId, keyId)` pairs; verified listener examples:

| Subscribed pair | Location |
|-----------------|----------|
| `(0x03, 0x11/0x12/0x13/0x14)` — auth | `p026be/f.java:114–122` |
| `(0x02, 0x50)` | `p150kd/j.java:207` (connector boot command; payload = u16 value 3) |
| `(0x02, 0x21)` | `Hd/q.java:45–48` |
| `(0x02, 0xB6)` | `Id/f.java:51` |
| `(0x02, 0x9E)` | `Kd/c.java:86` |

(Binding of many features to further opcodes was not exhaustively mapped — see §14.)

### 6.2 Auth state (`p026be/f.java`)

| Field | Meaning | Notes |
|-------|---------|-------|
| `f18015b` | `firstAuth` (starts `true`) | Forces the BIND path on first connection |
| `f18016c` | `userId` (stored, default `""`) | Change of userId forces `firstAuth = true` (`p150kd/j.java:289–297`) |
| `f18017d` | AuthMode (default AUTO) | Auto-resolves: first→BIND, later→LOGIN |
| `f18018e` | volatile flag (first attempt tracking) | — |
| `f18019f` | volatile token string | Session token (`Auth login:<token>`) |

Mode resolution at connect (`p150kd/j.java:271–306`): `bindOrLogin=true` → `p181me.a.BIND`,
`false` → `LOGIN`; if the requested userId differs from the stored one, `firstAuth` is forced
`true` and sync-times (`syncTimes<N>` SP keys) plus the EPO timestamp (`epo2_`) are cleared.

### 6.3 BIND / LOGIN packet sequences (verified, `p026be/f.java:110–123`)

| Step | BIND (`doBindAuth`, method `e()`) | LOGIN (`doLoginAuth`, method `f()`) |
|------|-----------------------------------|--------------------------------------|
| Request packet | `FcProtocolPacket(cmdId=0x03, keyId=0x12)` (null data) | `FcProtocolPacket(cmdId=0x03, keyId=0x14)` (null data) |
| Key packet | `FcProtocolPacket(cmdId=0x03, keyId=0x11, keyData=32-byte userId block)` — built by `p334x9.b.k(userId, true)` | `FcProtocolPacket(cmdId=0x03, keyId=0x13, keyData=32-byte userId block)` — `p334x9.b.k(userId, false)` |
| Timeout | 15 s (`TimeUnit.SECONDS`, line 115) | 15 s (line 122) |

`(Exact transmit ordering of the two packets is not fully resolved from the decompiled queue code
(sequencing helper `p363zd.b`); both packets belong to the named sequence.)`

**Key packet payload** (`p334x9/b.java:452–460`, method `k()`): a 32-byte block; the userId string
bytes (UTF-8) are copied into it zero-padded, max 28 bytes.

### 6.4 Session token

`AuthOperation.d(mac)` (`p026be/f.java:100–108`): generates
`Long.toString(System.currentTimeMillis(), 36)` — a base-36 timestamp token — stores it in
SharedPreferences `fc_internal_storage` under key `token2_<MAC-without-colons>` and returns it.
This matches the **hardware validation log** `Auth login: mu5z25th` (a base-36 millisecond
timestamp), recorded in `Reports/FitCloud_P6_Validation_Report.md` (Phase 3).

### 6.5 Hardware cross-check (P6, 2026-10-29)

| Observation | Value | Consistent with static analysis |
|-------------|-------|--------------------------------|
| Connect mode | `bindOrLogin=false` (LOGIN) | ✅ — P6 was already bound by the official app under `userId=26544037` |
| Log tags | `Fc#Connector`, `Fc#AuthOperation` | ✅ same tags found in the connector/auth classes |
| `doLoginAuth result:0` | success | ✅ — LOGIN request packet `(0x03, 0x14)` + key packet `(0x03, 0x13)` accepted |
| `Auth login: mu5z25th` | token | ✅ — base-36 timestamp token exactly as generated by `AuthOperation.d()` |
| Hardcoded `bluebit_user` fails login | — | ✅ — LOGIN authenticates the userId that was bound to the device; any other userId is rejected/mismatched |

---

## 7. Cryptographic Findings

### 7.1 AES-256-CTR on the FitCloud transport (`Aes256CtrUtil`)

**Confirmed.** `p334x9.b.o(int sn, byte[] data)` (`sources/p334x9/b.java:476–516`), logging under
tag `Aes256CtrUtil`:

```java
Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");          // line 498
cipher.init(1, new SecretKeySpec(f54121f, "AES"));                // line 499 — 32-byte key
byte[] keystream = cipher.doFinal(ivCopy);                        // line 500 — ECB on IV → keystream
// CTR: result[i] = data[i] ^ keystream[i % 16]                   // lines 504–508
```

- **Key:** static `p334x9.b.f54121f`, 32 bytes → **AES-256**.
- **IV/nonce:** static `p334x9.b.f54120e`, 16 bytes; per-packet the last two bytes are replaced
  with the packet **sequence number** (`IV[14] = sn >> 8; IV[15] = sn & 0xFF`, lines 486–489) —
  i.e. a standard CTR mode with a 16-bit packet counter.
- **Encryption is per-packet optional:** the transport header flag bit `0x40` (`U1.e` parse
  header, `U1/e.java:167`) announces an encrypted packet; decryption uses the packet SN as the
  CTR counter (`U1/e.java:141`). Outgoing traffic is encrypted only while the static flag
  `f12360g` is set, which is updated from the **received** header's `0x40` flag and
  **force-cleared when a key packet is transmitted** (see 7.3).

### 7.2 Session key / IV derivation (confirmed, `p150kd/j.java:382–424`)

Inputs: `userId` (string), device MAC `aa:bb:…`.

```text
userIdBlock[32]  = userId bytes (UTF-8), zero-padded, max 28 copied
mac[6]           = MAC octets
constBlock[32]   = { 0x4D, 0x4A, 0x42, 0x54, 0x55, 0xAA, 0xA5, 0x5A } × 4   // p150kd/j:394-398
                   (ASCII "MJB" + 0x54/0x55 + 0xAA + 0xA5 + 0x5A)

AES-256 key f54121f[32]:   key[i]  = (i < 6 ? mac[i] : 0x00) ^ userIdBlock[i] ^ constBlock[i]
Nonce    f54120e[16]  =   { 0xD4, 0xEF, 0xF0, 0xD3, 0xF4, 0xE5, 0xF0,
                            mac[0], mac[1], mac[2], mac[3], mac[4], mac[5],
                            0xA1, 0x00, 0x00 }                          // p150kd/j:399-417
```

- Derived once per connection (`p150kd/j.java:417,424`), cleared at disconnect (lines 256–257).
- userId assembly uses `Math.min(bytes.length, 28)` (`p150kd/j:385`) — consistent with the
  key-packet payload limit in `p334x9.b.k()`.

### 7.3 The key packets themselves travel as plaintext

**Confirmed.** In the packet encoder `U1.e.b()` (`U1/e.java:76–78`), if the outgoing payload is an
auth key packet (`payload[0]==3 && payload[2]==17||19`, i.e. cmdId `0x03` with keyId `0x11` or
`0x13`), the global `f12360g` (encryption-enabled) flag is set to `false`, so that frame is
written to the GATT characteristic **unencrypted**. (Both key packets carry the userId-block key
material in the clear; encryption only resumes per PEER ACK.)

### 7.4 Security assessment

| Aspect | Rating | Reason |
|--------|--------|--------|
| Cipher | AES-256-CTR | Real designation in code (`Aes256CtrUtil`); algorithm is sound |
| Key entropy / secrecy | ❌ Weak | Derived deterministically from **public inputs**: userId (36-bit numeric, e.g. `26544037`), MAC (public in advertisements), and a fixed constant. No server/peer contribution, no ECDH, no KDF with salt |
| Key exchange | ❌ None | Key packets are transmitted as plaintext; the "key exchange" is really key **announcement** |
| Forward secrecy | ❌ None | Static derivation from static inputs |
| Replay protection | ⚠️ Weak | 16-bit packet SN only (encrypts each packet with IV = base‖SN); SN wrap and cross-session replay are plausible |
| Integrity | ⚠️ CRC16, not MAC | Header CRC16 (table `p339xe.j.f54196a`) is transport integrity, not an authenticator; ciphertext malleability is unmitigated |
| vs. Fitbit Inspire 3 auth | Completely different | Fitbit: ephemeral Gattlink + DTLS handshake with certificates |

**Bottom line:** traffic to a registered device (with the corresponding `userId`) is encrypted
with keys anyone observing the userId and MAC can derive; a passive attacker who also captures
the (plaintext) key packets gets everything without even deriving the keys. This is
obfuscation-grade protection, not authentication-grade.

### 7.5 Unverified / open crypto questions

- Whether the device-side ever enables the `0x40` encrypted flag for a secure (non-auth)
  channel, or whether sessions remain fully plaintext from the device side.
- The exact serialization layout of `FcProtocolPacket` bytes on the wire (see §14).

---

## 8. Packet Format (FitCloud tracker transport)

**Verified source:** `sources/U1/e.java` (log tag `Fc#TransportPacket`).

### 8.1 Data frame

```text
Header (8 bytes)
[0]     0xAA            magic (constant from StatusIndex.ANC_MODE, value −85)
[1]     flags:          0x10 = ACK/ack-marker bit   (parse: `(b2 & 16) != 0`, U1/e:53,166)
                        0x20 = success bit on ACK   (parse: `(b2 & 32) != 0`, U1/e:54)
                        0x40 = encrypted flag        (parse: `(b2 & 64)`, U1/e:167; encode: `| 64`)
[2..3]  payload length  uint16 BIG-endian           (U1/e:168)
[4..5]  CRC16           uint16 BIG-endian, table-driven over payload (p339xe.j.f54196a)
[6..7]  SN / counter    uint16 BIG-endian; also the AES-CTR per-packet counter (U1/e:170)

Payload (variable)
  unencrypted: serialized FcProtocolPacket [cmdId, keyId, keyData…]
  encrypted : AES-256-CTR(SN) over the same bytes
```

- Frame assembly confirms header layout: `{0xAA, flags, len>>8, len, crc>>8, crc, sn>>8, sn}`
  (`U1/e.java:79`).
- Long payloads are chunked; each chunk is (re-)encrypted with the same base SN IV and appended
  (`U1/e.java:90–104`); a 1024-byte receive buffer accumulates chunks (`U1/e.java:34,112–133`).

### 8.2 ACK frame

8 bytes — header only (no payload):

```java
// U1/e.java:45–56 — ack parse
if (bArr[0] != 0xAA) return null;
if ((b[1] & 0x10) != 0 && b[2..5] are 0)   // ack marker with zero length/crc
    return new ack( success = (b[1] & 0x20) != 0, SN = (b[7] & 0xFF) | (b[6] << 8) );
```

### 8.3 BBPro transport frame (audio stack — NOT the FitCloud tracker frame)

`com.realsil.sdk.bbpro.core.transportlayer.TransportLayerPacket`: 4-byte header — `0xAA` sync, 1
byte seq, 2-byte LE length — then opcode+payload; `AckPacket` = 2-byte toAckId + 1-byte status
with status constants (`ACK_STATUS_COMPLETE=0` … `CMD_SET_STATUS_GATT=17`). This format applies
to the Realtek audio stack and was **mistakenly** presented as the FitCloud frame in report v1.

### 8.4 Preview/camera stream (own mini-protocol)

`PreviewFeatureImpl` (SDK v2) opens its own GATT session on the Nordic-UART-compatible service
(§5.3) and streams JPEG frames with quality control (`isSending`, `jpegQuality`,
`writeDataDisposable` fields).

---

## 9. Device-Specific Implementation Findings

### 9.1 Family handling

- Device variants are distinguished at scan time by company ID (SDK `FcScanner`: `0x5448`,
  `0x4254`, `0x5A54`) and by the advertised `00003802` service vs. the runtime GATT content
  (`Nd` path expects `00004a02` inside a real `0x3802` service group).
- The connector's default GATT target (`01ff` + `ff02`/`ff03`) is used for devices whose actual
  connected GATT matches that set — which is exactly the P6 (§11).
- Feature-gating happens through capability values carried in device-info packets; the dial
  parser reads capability bits `h(284)`, `h(285)`, `h(286)` with shape/fallback logic
  (`p334x9/b.java:120–211`) — a direct example of device-variant-aware capability decoding.
- Additional per-family channels observed: AliAgent voice data on `01ff` service chars
  `ff04`/`ff05` (`Kd/e.java:43–53`); file/asset transfer on `9966` service chars `ffb1`/`ffb2`;
  secondary services `aa55` and `6587` (roles not determined).

### 9.2 P6 profile (hardware-validated + APK cross-check)

| Property | Value | Source |
|----------|-------|--------|
| Name / MAC | `P6_` / `90:EB:99:D6:5F:99` | hardware |
| Company ID | `0x5A54` (23124) | hardware + SDK scanner |
| Advertised service | `00003802-0000-1000-8000-00805F9B34FB` | hardware |
| `0x3802` in connected GATT | **NO** (advertisement-only) | hardware GATT dump |
| Connected services | `1800`, `ff12` (+ff13/ff14/ff15), `000001ff-3c17-…`(+ff02/ff03), `0000d0ff-3c17-…`(+ffd1-ffd5,ffd8,fff1-fff3,ffe0), `00006287-3c17-…`(+6387/6487) | hardware GATT dump (5 services) |
| All char permissions | `NONE` (no GATT-level encryption) | hardware dump |
| SDK connect | LOGIN with `userId=26544037` → CONNECTED, `doLoginAuth result:0`, token `mu5z25th` | hardware |
| `getDeviceInfo()` | Project `0000000052B6`, Patch 0, Flash 0, App 101, IC `IC_8762C` | hardware |
| `requestDialPushInfo()` | dial num 19, bin ver 1, position 0, tool 1.5, spaces 0 | hardware |
| `requestUiInfo()` | UI num 3661, serial 1, style 19 | hardware |
| `requestLcdShape()` | ❌ **Consistently fails ("unknown error")** — LCD shape must be taken from `requestDialPushInfo()` response instead | hardware |
| AliAgent voice channel (`ff04/ff05`) | Not present in P6 GATT | hardware |

**APK-side GATT correlation:** the connector default (`01ff` + `ff02` W + `ff03` R/WNR/N with
CCCD) matches the P6's service [2] 1:1, and the dial service (`6287` + `6387` WNR + `6487` W/N
with CCCD) matches service [4] 1:1. This is strong static+dynamic confirmation that the Fitcloud
SDK's transport is the P6's protocol.

### 9.3 Capabilities

The decompiled capability tables (dial/UI/notification/etc. flags) are spread over dozens of
feature classes and were **not fully enumerated** in this pass (v1 overstated "150+ flags"; no
single table of that size was located). Verified explicit capability reads: dial-packet
capability bits `h(284)/h(285)/h(286)` with distinct phone/firmware layout variants
(`p334x9/b.java`). Everything else is unverified and marked as such in
`DEVICE_COMPATIBILITY.md`.

### 9.4 DFU / OTA

`Fc#DfuManager` (`Fd/j.java`) manages firmware/resource updates by dfuType and shares the
transport. Static opcodes for DFU sub-commands were **not** resolved in this pass (v1's opcode
claims were unverified and have been removed).

---

## 10. Inspire 3 Compatibility Assessment

**Result: ABSENT.**

| Check | Matches |
|-------|---------|
| `(?i)inspire|fitbit` over all decompiled sources | **0** |
| Fitbit Gattlink UUIDs (`ADABFB00…`, `ABBAFF00…`, `ABBAFF01/02`) | 0 |
| Modern Fitbit services (`0000FD62`, `0000FD63`) | 0 |
| Fitbit control chars (`AC2F0045`, `AC2F0145`) | 0 |
| Gattlink / GoldenGate / DTLS / CoAP protocol logic | 0 |
| libxp.so / GoldenGate native traces | 0 |

| Aspect | FitCloudPro (P6 path) | Fitbit Inspire 3 |
|--------|----------------------|------------------|
| Transport framing | `Fc#TransportPacket` (0xAA, CRC16, SN, optional AES-CTR) | Gattlink (L2CAP/custom) |
| Auth | BIND/LOGIN with userId key-material packets | Ephemeral keys + DTLS handshake |
| Encryption | AES-256-CTR with statically derivable key | DTLS/AES with true key exchange + certs |
| UUID base | Topstep `-3c17-d293-8e48-14fe2e4da212` | Fitbit `-6E7D-…` / `-E56A-…` |

**Conclusion:** FitCloudPro contains zero Fitbit support. The two ecosystems share nothing.

---

## 11. Correlation with BlueBit Evidence

### 11.1 P6 findings

| BlueBit finding | APK confirmation |
|-----------------|------------------|
| P6 advertises `0x3802`; **absent from connected GATT** | ✅ `Nd` path expects a `0x3802` service + `4a02` char in the connected GATT; on the P6 that service never appears, so this path is inactive — consistent with the hardware dump |
| P6 connected GATT has `000001ff-3c17-…` (ff02 WRITE / ff03 R,WNR,N + CCCD) | ✅ Exactly the connector default transport (`p150kd/j.java:155–161`) |
| P6 connected GATT has `00006287-3c17-…` (6387 WNR, 6487 W+N+CCCD) and `0000d0ff-3c17-…` | ✅ Dial service/config constants in `p118ia/f.java`, `p118ia/k.java` |
| P6 advertises companyId `0x5A54` | ✅ In the SDK scanner's accepted set |
| P6 `ff12` service (ff13/14/15) present in the dump | ⚠️ Also listed among BBPro audio-profile characteristics; its role on the P6 (an unmapped service with no permissions required) remains unresolved |
| SDK login works only with the official-app-bound userId | ✅ Auth flow is userId-keyed end-to-end (vr. §6) |

### 11.2 Inspire 3 blockers

None of BlueBit's Inspire 3 blockers (Gattlink ephemeral session, DTLS bootstrap, `bond/control`
CoAP, `AC2F0145` ephemeral pointer) have any counterpart in FitCloudPro — there is nothing in
this APK that could explain or shortcut the Fitbit authentication blocker.

---

## 12. Important Source Files

| Component | jadx file | Notes |
|-----------|-----------|-------|
| Connector | `sources/p150kd/j.java` | lines 128–218 (config/init), 271–379 (connect+auth resolve), 382–424 (key derivation) |
| Transport packet | `sources/U1/e.java` | whole file (frame/ACK/encrypt/chunk logic) |
| Auth | `sources/p026be/f.java` | whole file (bind/login, token) |
| Protocol packet | `sources/p349yd/a.java`, `sources/p349yd/b.java` | whole files |
| AES-CTR util | `sources/p334x9/b.java` | `o()` lines 476–516; `k()` lines 452–460; dial parser `f()` |
| Audio stack | `sources/com/realsil/sdk/bbpro/**` | `TransportLayer(.Packet)`, `GattLayer`, `GattTransportLayer`, `AckPacket` |
| 0x3802 detection | `sources/Nd/e.java`, `sources/Nd/b.java` | whole files |
| DFU | `sources/Fd/j.java` | tag `Fc#DfuManager` |
| Voice channel | `sources/Kd/e.java` | AliAgentSdk on `ff04/ff05` |
| File transfer | `sources/Fd/F.java`, `sources/p166ld/d.java` | `9966` service, `ffb1/ffb2` |
| Scanner filters | `sources/na/a.java`, `sources/p178ma/c.java`, `sources/p222pa/b.java` | `020002fd` literal |
| Preview | `sources/com/topstep/fitcloud/sdk/v2/features/builtin/preview/PreviewFeatureImpl.java` | Nordic-UART service |

---

## 13. Confirmed Findings vs Hypotheses

| # | Finding | Status | Primary evidence |
|---|---------|--------|------------------|
| 1 | Two BLE stacks (FitCloud tracker + Realtek audio) | ✅ Confirmed | package tree + `p150kd.j` RxAndroidBle config vs `com.realsil.sdk.bbpro` usage |
| 2 | FitCloud transport = 0xAA 8-byte header, CRC16, 16-bit SN | ✅ Confirmed | `U1/e.java:79,149–183` |
| 3 | Optional AES-256-CTR packet encryption, flag 0x40 | ✅ Confirmed | `p334x9/b.java:476–516`, `U1/e.java:141,167` |
| 4 | Key = MAC ⊕ userId ⊕ fixed constants; IV embeds MAC | ✅ Confirmed | `p150kd/j.java:382–424` |
| 5 | Auth key packets sent plaintext | ✅ Confirmed | `U1/e.java:76–78` |
| 6 | BIND `(0x03,0x12)`+`(0x03,0x11)`; LOGIN `(0x03,0x14)`+`(0x03,0x13)`; token2_<mac> | ✅ Confirmed | `p026be/f.java:110–123` + hardware log token format |
| 7 | Connector default GATT `01ff`+`ff02`/`ff03`; alt `3802`+`4a02` | ✅ Confirmed | `p150kd/j.java:155–161`, `Nd/e.java`, `Nd/b.java:40–47` |
| 8 | Dial service/config UUIDs `6287/6387/6487`, `d0ff` family | ✅ Confirmed | `p118ia/f.java`, `p118ia/k.java` + identical values in P6 hardware dump |
| 9 | File transfer service `9966` + `ffb1/ffb2` | ✅ Class existence confirmed; exact semantics ⚠️ inferred from `File` flow | `Fd/F.java`, `p166ld/d.java` |
| 10 | Voice/AIGC channel `ff04/ff05` | ✅ Confirmed | `Kd/e.java` |
| 11 | `Fc#DfuManager` exists; DFU opcodes mapped | first half ✅, opcodes ❌ not mapped | `Fd/j.java` |
| 12 | "150+ capability flags" (v1) | ⚠️ Corrected — not verified; capability bits 284/285/286 verified in dial parser | `p334x9/b.java` |
| 13 | Role of services `aa55`, `6587`, literal `020002fd`, and of the `ff12` service on P6 | ❓ Unknown | presence only |
| 14 | Device-side behaviour of the 0x40 encryption flag and of key-packet acceptance | ❓ Unknown (needs a logged capture with the real userId) | — |

---

## 14. Unanswered Research Questions

1. Exact byte serialization of `FcProtocolPacket` (position of `cmdId`/`keyId` in the enclosed
   payload — verified positions exist only via the encrypt-bypass check `payload[0]==3`,
   `payload[2]==17|19` and the ACK-parse offsets).
2. Which commands trigger the device to set the `0x40` encryption flag (is every authenticated
   session encrypted after auth, or only specific features?).
3. Complete `(cmdId, keyId)` → feature map (only auth + a few device-info/voice/DFU opcodes were
   resolved).
4. The role of services `0000aa55`, `00006587`, the literal scan filter `020002fd`, and service
   `ff12` as seen on the P6.
5. DFU resource/firmware sub-protocol (opcodes, chunking, signing).
6. Whether the cloud API reuses the same key material (not examined — cloud analysis out of
   scope for BLE research and involves account data).

---

## 15. Recommended Next Research Steps

1. **Static:** resolve `p363zd.b`/`O7.f` sequencing to pin the exact BIND/LOGIN packet order and
   the full serialization of `FcProtocolPacket`.
2. **Static:** enumerate the `(cmdId, keyId)` subscriptions across all feature classes to build a
   complete opcode map (grep of `f54556a ==`/`f54557b ==` comparisons).
3. **Dynamic (read-only, no device writes):** run the official app while logging Bluetooth HCI
   (a different phone that supports btsnoop — Samsung A17 reverts the setting; see validation
   report) to observe whether authenticated sessions actually carry the `0x40` flag, and whether
   the observed on-air bytes match the §7 key derivation with `userId=26544037`.
4. **Dynamic:** replicate the derived key/IV in a PC-side capture-decrypt script to validate the
   §7 derivation against a real capture (no writes to the device).
5. **Static:** map `Fd.j` (`Fc#DfuManager`) dfu types to packets — needed only if BlueBit ever
   needs managed updates (out of scope for read-only integration).

---

## 16. Deliverable Locations

| File | Path |
|------|------|
| Main report | `G:\Dev\BlueBit\research\fitcloud-apk\REPORT.md` |
| UUID inventory | `G:\Dev\BlueBit\research\fitcloud-apk\UUID_INVENTORY.md` |
| Authentication analysis | `G:\Dev\BlueBit\research\fitcloud-apk\AUTHENTICATION_ANALYSIS.md` |
| Device compatibility | `G:\Dev\BlueBit\research\fitcloud-apk\DEVICE_COMPATIBILITY.md` |
| Machine-readable findings | `G:\Dev\BlueBit\research\fitcloud-apk\findings.json` |
| Original APKs | `G:\Dev\BlueBit\research\fitcloud-apk\original\` |
| Decompilements | `G:\Dev\BlueBit\research\fitcloud-apk\decompiled\jadx|apktool|apktool_arm64|apktool_en|apktool_xxhdpi` |

---

## 17. Final Summary

| Question | Answer |
|----------|--------|
| APK successfully extracted & decompiled? | ✅ Yes (4 splits, JADX 1.5.6 + Apktool 2.11.0) |
| BLE architecture located? | ✅ Yes — FitCloud tracker stack on RxAndroidBle (+ separate Realtek audio stack) |
| Authentication located? | ✅ Yes — BIND/LOGIN key-packet protocol; session token `token2_<mac>` |
| Encryption identified? | ✅ Yes — AES-256-CTR with statically derivable key/IV; key packets sent plaintext |
| Inspire 3 support found? | ❌ Absent (zero evidence after correction pass) |
| Findings explain BlueBit's Inspire 3 blocker? | ❌ No — entirely different protocol ecosystems |
| Deliverables created? | ✅ Yes (5 files + preserved APKs) |

---

## Appendix A — Correction log (v1 → v2)

| v1 claim (incorrect) | v2 (verified) |
|----------------------|---------------|
| "FitCloudPro uses RxAndroidBle → Realtek BBPro SDK → TransportLayer" for the P6 | FitCloud tracker traffic goes over `Fc#Connector`/`Fc#TransportPacket`/RxAndroidBle; the BBPro transport is a separate audio-stack path |
| "No AES encryption on BLE transport… XOR-based session key" | AES-256-CTR exists (`Aes256CtrUtil`); the derived key/IV are XOR-assembled (that was the source of confusion), but the cipher is AES |
| `TransportLayerPacket` 4-byte header + 2-byte LE opcode = the P6 packet format | FitCloud frame = 8-byte header (0xAA, flags, len BE, CRC16 BE, SN BE); BBPro packet format applies to the audio stack |
| BIND cmd `0x12`, LOGIN cmd `0x14`/`0x15` (Mmi values 18/20/21) | Auth packets use cmdId `0x03` with keyIds `0x11/0x12` (bind) and `0x13/0x14` (login); `Mmi` constants used in key derivation are `0x54/0x55/0xAA/0xA5/0x5A/0xD4/0xF0/0xD3` |
| "LCD shape 368×448 (from `requestLcdShape()`)" in the P6 profile | `requestLcdShape()` **fails** on the P6 (hardware-validated); shape must come from `requestDialPushInfo()` |
| P6 "4G modem + GNSS versions via `getExtraFirmwareInfo()`" | Not tested in validation; removed |
| "0000ff02/ff03 source = `GattTransportConnParams.f36482g/f36483h`" | `GattTransportConnParams` chars are `0000fd03/fd04` (audio profile); `ff02/ff03` come from `p150kd/j.java:156–159` |
| Capability table "150+ flags" | Not verified; only bits 284/285/286 verified |
| DFU opcodes `0x49/0x15/0x004D` | Unverified — removed |

*End of report (v2).*
