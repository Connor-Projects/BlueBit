# FitCloudPro UUID Inventory

**Generated:** 2026-10-30 (v2 — corrected after source-verification pass)
**Source:** Static analysis of `com.topstep.fitcloudpro` APK (JADX 1.5.6 + Apktool 2.11.0)
Cross-referenced with the physical P6 GATT dump: `Reports/p6_gatt_dump_20261029_222023.txt`.

> **Short UUID convention:** rows written as `XXXX` are expanded on the standard Bluetooth base
> `0000XXXX-0000-1000-8000-00805F9B34FB` (constructor at `sources/C9/b.java:66`).
> Topstep-specific UUIDs use the custom base `-3c17-d293-8e48-14fe2e4da212`.

---

## 1. FitCloud tracker stack (the P6's protocol family)

### 1.1 Connector default transport (verified `sources/p150kd/j.java:155–161`)

| UUID | Kind | Role | Properties (P6 dump) |
|------|------|------|----------------------|
| `000001ff-3c17-d293-8e48-14fe2e4da212` | Service | Primary FitCloud transport | ✅ on P6 |
| `0000ff02-0000-1000-8000-00805f9b34fb` | Char | Write (command channel) | WRITE |
| `0000ff03-0000-1000-8000-00805f9b34fb` | Char | Notify/status channel | READ + WRITE_NO_RESPONSE + NOTIFY + CCCD `2902` |

### 1.2 Advertised-service path (`sources/Nd/e.java:29–36`, `sources/Nd/b.java:29–49`)

| UUID | Kind | Role | On P6 |
|------|------|------|-------|
| `00003802-0000-1000-8000-00805f9b34fb` | Service | Scan/identity discriminator | ⚠️ **Advertisement only** — not present in connected GATT |
| `00004a02-0000-1000-8000-00805f9b34fb` | Char | Characteristic expected inside a real `0x3802` service group | ❌ Absent (path unused) |

### 1.3 Dial / watch-face and config services (verified `p118ia/f.java`, `p118ia/k.java`; confirmed by P6 dump)

| UUID | Kind | Role | On P6 |
|------|------|------|-------|
| `00006287-3c17-d293-8e48-14fe2e4da212` | Service | Dial/watch-face service | ✅ |
| `00006387-3c17-d293-8e48-14fe2e4da212` | Char | Dial write | WRITE_NO_RESPONSE |
| `00006487-3c17-d293-8e48-14fe2e4da212` | Char | Dial status | WRITE + NOTIFY + CCCD |
| `0000d0ff-3c17-d293-8e48-14fe2e4da212` | Service | Config / feature characteristics | ✅ |
| `0000ffd1–ffd8`, `0000ffe0`, `0000fff1–fff3` (0000-1000-8000-…) | Chars | Config/feature data | ffd1 WNR; ffd2–ffd5, ffd8, ffe0, fff1–fff3 READ |

### 1.4 Additional device-variant channels (class exists; role inferred or unknown)

| UUID | Kind | Role | Evidence | On P6 |
|------|------|------|----------|-------|
| `0000ff04-0000-1000-8000-…` | Char | Voice/AIGC (AliAgentSdk) data | `Kd/e.java:50–52` | ❌ |
| `0000ff05-0000-1000-8000-…` | Char | Voice/AIGC data | `Kd/e.java:47–49` | ❌ |
| `00009966-3c17-d293-8e48-14fe2e4da212` | Service | File/asset transfer (file flow) | `Fd/F.java:35`, `p166ld/d.java:33`, `p039ce/a.java:15`, `Hd/C0490a.java:13` | ❌ |
| `0000ffb1-0000-1000-8000-…` | Char | File-transfer write | `p166ld/d.java:36–38` | ❌ |
| `0000ffb2-0000-1000-8000-…` | Char | File-transfer notify | `p166ld/d.java:39–41` | ❌ |
| `00006587-3c17-d293-8e48-14fe2e4da212` | Char | Unknown device-variant channel | `p206oa/d.java:10` | ❌ |
| `0000aa55-3c17-d293-8e48-14fe2e4da212` | Service | Unknown secondary channel | `Hd/C0497h.java:38–39` | ❌ |
| `020002fd-3c17-d293-8e48-14fe2e4da212` | Service | Scan-filter literal (exact string in code) | `na/a.java:60`, `p178ma/c.java:139`, `p222pa/b.java:166` | ❌ |

> **Note:** v1 of this inventory listed a `0000ffd1–fff3 "read-only dial/config"` family with
> invented read roles and omitted `ffe0`; corrected above from the hardware dump. The v1 rows
> attributing `ff02/ff03` to `GattTransportConnParams` were wrong — those params use
> `0000fd03/fd04` (audio profile, §2).

---

## 2. Realtek BBPro audio stack (BeePro earbuds — separate path from the P6)

| UUID | Kind | Role | Evidence |
|------|------|------|----------|
| `000002fd-3c17-d293-8e48-14fe2e4da212` | Service | BBPro GATT profile default service | `GattTransportConnParams.java:11` |
| `0000fd03-0000-1000-8000-…` | Char | BBPro profile char (= `b.b(64771)`) | `GattTransportConnParams.java:14` |
| `0000fd04-0000-1000-8000-…` | Char | BBPro profile char (= `b.b(64772)`) | `GattTransportConnParams.java:15` |
| `0000ff12-0000-1000-8000-…` | Service (+ chars `ff13` WRITE, `ff14` NOTIFY+CCCD, `ff15` READ+WNR) | Audio-device GATT transport (⚠️ also present as service [1] in the P6 dump — role on P6 unresolved) | `GattLayer.java:15–17`; P6 dump |
| `00001101-0000-1000-8000-…` | Service | Standard SPP (classic BT) | `D9/c.java:22` |
| `6A24EEAB-4B65-4693-986B-3C26C352264F` | Service | Realtek vendor SPP (classic BT) | `D9/c.java:23` |
| `0000180F` / `00002A19` | Service/Char | Battery (audio side) | `p118ia/c.java` |
| `0000180A` + `00002A29`, `00002A23`, `00002A50` | Service/Chars | Device Information (audio side) | `p118ia/g.java` |

---

## 3. Preview / camera streaming (Nordic UART compatible)

| UUID | Role | Evidence |
|------|------|----------|
| `6e400001-b5a3-f393-e0a9-e50e25dcca8d` | Service | `PreviewFeatureImpl.java:58–60` |
| `6e400002-b5a3-f393-e0a9-e50e25dcca8d` | TX (write JPEG frames) | same, 64–66 |
| `6e400003-b5a3-f393-e0a9-e50e25dcca8d` | RX (notify) | same, 61–63 |

---

## 4. CCCD

Every notify characteristic above carries the standard Client Characteristic Configuration
Descriptor `00002902-0000-1000-8000-00805f9b34fb` (also confirmed on the P6: `ff14`, `ff03`,
`6487`).

---

## 5. Company IDs (manufacturer data)

| ID | Vendor | Context |
|----|--------|---------|
| `0x5A54` (23124) | Vendor-specific Topstep/FitCloud | P6 advertisement; accepted by SDK scanner (along with `0x5448`, `0x4254` — per SDK documentation, see `Reports/FitCloud_SDK_Compatibility_Report.md`) |

---

## 6. Realsil constants reused as protocol bytes (not UUIDs — for completeness)

| Constant | Value | FitCloud usage |
|----------|-------|----------------|
| `StatusIndex.ANC_MODE` | `0xAA` | Transport magic byte |
| `StatusIndex.AVP_MULTI_LINK_STATE` | `0xA5` | Key-derivation constant |
| `Mmi.AU_MMI_DEV_POWER_ON_BUTTON_PRESS/RELEASE` | `0x54`/`0x55` | Key-derivation constants |
| `Mmi.AU_MMI_DEV_FACTORY_RESET_BY_SPP` | `0x5A` | Key-derivation constant |
| `Mmi.AU_MMI_AIRPLANE_MODE` | `0xD4` | IV[0] |
| `Mmi.AU_MMI_START_ROLESWAP` | `0xF0` | IV[2], IV[6] |
| `Mmi.AU_MMI_LIGHT_SENSOR_ON_OFF` | `0xD3` | IV[3] |
| `Mmi.AU_MMI_DISC_LINK_AND_ENTER_PAIRING_MODE` | `0x50` | keyId of connector boot command |

---

## 7. Notable ABSENT UUIDs — Fitbit/Inspire 3 (compatibility check)

| UUID family | Fitbit role | In APK |
|-------------|-------------|--------|
| `ADABFB00-6E7D-4601-BDA2-BFFAA68956BA` | Fitbit Gattlink service | ❌ 0 matches |
| `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` (+`ABBAFF01`, `ABBAFF02`) | Fitbit Gattlink chars | ❌ 0 matches |
| `0000FD62`, `0000FD63` | Fitbit modern Gattlink services | ❌ 0 matches |
| `AC2F0045`, `AC2F0145` | Fitbit control / ephemeral chars | ❌ 0 matches |
| Any literal "Inspire"/"Fitbit" string | — | ❌ 0 matches |

---

## 8. P6 hardware GATT cross-reference (authoritative: `Reports/p6_gatt_dump_20261029_222023.txt`)

| Service [n] | UUID | Chars |
|-------------|------|-------|
| 0 | `00001800-…` Generic Access | `2a00` READ, `2a01` READ |
| 1 | `0000ff12-…` | `ff13` WRITE; `ff14` NOTIFY+CCCD; `ff15` READ+WNR |
| 2 | `000001ff-3c17-…` | `ff03` READ+WNR+NOTIFY+CCCD; `ff02` WRITE |
| 3 | `0000d0ff-3c17-…` | `ffd1` WNR; `ffd2–ffd5`, `ffd8`, `ffe0`, `fff1–fff3` READ |
| 4 | `00006287-3c17-…` | `6387` WNR; `6487` WRITE+NOTIFY+CCCD |

All permissions `NONE` (no GATT-level encryption). Advertised `00003802` **not** present.

*End of UUID Inventory (v2).*
