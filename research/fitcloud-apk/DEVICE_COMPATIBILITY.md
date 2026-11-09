# FitCloudPro Device Compatibility Analysis

**Generated:** 2026-10-30 (v2 — corrected after source-verification pass)
**Source:** Static analysis of `com.topstep.fitcloudpro` APK
**Hardware validation:** `Reports/FitCloud_P6_Validation_Report.md` (2026-10-29)
**GATT dump:** `Reports/FitCloud_P6_GATT_Discovery.md`

> Evidence tags: **✅ Confirmed** (code + hardware), **⚠️ Inferred** (code only),
> **❓ Unknown**. v1 of this analysis contained unverified claims (P6 LCD shape, 150-flag table,
> DFU opcodes) that are corrected or removed here.

---

## 1. Overview

FitCloudPro is a companion app for the **Topstep FitCloud tracker family** (Realtek SoCs, e.g.
`IC_8762C`) plus a separate **Realtek BeePro audio (earbuds) family**. The app contains **no
Fitbit/Inspire 3 support**.

---

## 2. Device families identified

### 2.1 FitCloud tracker family (P6's family) — ✅ Confirmed

| Attribute | Value |
|-----------|-------|
| Transport service | `000001ff-3c17-d293-8e48-14fe2e4da212` |
| Write char | `0000ff02-0000-1000-8000-00805f9b34fb` |
| Notify char | `0000ff03-0000-1000-8000-00805f9b34fb` (+ CCCD) |
| Framing | `Fc#TransportPacket` — 0xAA magic, CRC16, 16-bit SN, optional AES-256-CTR |
| Identity | Company ID set `0x5448`, `0x4254`, `0x5A54` (scanner); `00003802` advertised service; alt path expects `00004a02` char in a real `3802` service group |
| Known device | P6 (`IC_8762C`) |
| Auth | userId BIND/LOGIN key packets (see `AUTHENTICATION_ANALYSIS.md`) |

### 2.2 Realtek BeePro audio family — ⚠️ Inferred scope (present in app)

| Attribute | Value |
|-----------|-------|
| GATT profile default | service `000002fd-3c17-…` chars `0000fd03`/`0000fd04` (`GattTransportConnParams`) |
| Audio GATT transport | `ff12` service + `ff13/ff14/ff15` chars |
| Classic BT | SPP (`00001101`, vendor `6A24EEAB-…`) |
| Purpose | Audio earbuds/headphones control paths (`SongPushViewModel`, `BookViewModel`, media control) — distinct from tracker traffic |

### 2.3 Special channels — ✅ classes confirm existence; ⚠️ semantics inferred

| Channel | UUIDs | Purpose |
|---------|-------|---------|
| Voice/AIGC (AliAgent) | service `000001ff-3c17-…` + chars `ff04`/`ff05` | Speech AI data path (`Kd/e`) |
| File/asset transfer | service `00009966-3c17-…` + chars `ffb1`/`ffb2` | File flow in code (`Fd.F`, `p166ld.d`) |
| Secondary services | `0000aa55-3c17-…`, `00006587-3c17-…`, literal `020002fd-3c17-…` | Role unresolved |

---

## 3. Capability decoding (⚠️ partial — corrected)

The v1 claim of a "150+ feature flags table" was **not verified**. What static analysis actually
showed:

- Capability bits are carried in device-info/dial packets and read inside feature code. The dial
  parser (`p334x9.b.f()`) reads bits `h(284)`, `h(285)`, `h(286)` to select wire layouts (legacy
  vs v3, presence of tool fields, extra array formats) with shape fallbacks
  (`p334x9/b.java:120–211`).
- Feature classes subscribe to specific `(cmdId, keyId)` opcodes (examples: `(0x02, 0x21)`
  `Hd/q.java`, `(0x02, 0xB6)` `Id/f.java`, `(0x02, 0x9E)` `Kd/c.java`), so features are enabled
  per-opcode at runtime.
- A complete capability enumeration was **not** performed (limitation noted in
  `REPORT.md` §14/§15).

---

## 4. P6 profile (hardware-validated 2026-10-29 + APK cross-check)

| Property | Value | Evidence |
|----------|-------|----------|
| Name / MAC | `P6_` / `90:EB:99:D6:5F:99` | ✅ hardware |
| Company ID | `0x5A54` (23124) | ✅ hardware + SDK scanner set |
| Advertised service | `00003802-0000-1000-8000-00805F9B34FB` | ✅ hardware |
| `0x3802` in connected GATT | **No** — advertisement-only | ✅ hardware |
| Connected services (5) | `1800`; `ff12` (ff13/ff14/ff15); `000001ff-3c17-…` (ff02/ff03); `0000d0ff-3c17-…` (ffd1–ffd5, ffd8, ffe0, fff1–fff3); `00006287-3c17-…` (6387/6487) | ✅ hardware |
| Characteristic permissions | all `NONE` (no GATT-level encryption) | ✅ hardware |
| SDK connect | LOGIN `bindOrLogin=false`, `userId=26544037` → CONNECTED, `doLoginAuth result:0`, token `mu5z25th` | ✅ hardware |
| `getDeviceInfo()` | Project `0000000052B6`, Patch 0, Flash 0, App 101, IC `IC_8762C` | ✅ hardware |
| `requestDialPushInfo()` | dial num 19; bin version 1; position 0; tool 1.5; spaces 0 | ✅ hardware |
| `requestUiInfo()` | UI num 3661; serial 1; style 19 | ✅ hardware |
| `requestLcdShape()` | ❌ **Consistently fails ("unknown error")** — use `requestDialPushInfo()`'s shape data as fallback | ✅ hardware |
| Voice channel `ff04/ff05` | Absent from P6 GATT | ✅ hardware |
| Extra firmware info (GNSS/4G modem) | **Not queried during validation** — no claim possible | — |

### 4.1 SDK↔hardware GATT correlation (why the SDK works on the P6)

| APK constant | Match |
|--------------|-------|
| Connector default transport `01ff` + `ff02` (W) / `ff03` (R+WNR+N+CCCD) — `p150kd/j.java:155–161` | ✅ identical to P6 service [2] |
| Dial service `6287` + `6387` (WNR) / `6487` (W+N+CCCD) — `p118ia/f.java:9–15` | ✅ identical to P6 service [4] |
| `d0ff` config family — `p118ia/k.java` | ✅ identical to P6 service [3] |
| `3802` advertised service + `4a02` char path — `Nd/e.java`/`Nd/b.java` | ⚠️ `3802` matches the P6 **advertisement**; the char path is inactive because the service never appears in the connected GATT (matches BlueBit's finding) |
| Battery/Device-Info (`180F`/`180A`) | ❌ not present in the P6 dump (they belong to the audio stack path) |

This explains the SDK validation result (decision **B — Partial Compatibility**): authentication
and core queries run over `01ff`, which the P6 really exposes; the single failed query
(`requestLcdShape()`) is a feature gap of this device variant, not a protocol mismatch.

---

## 5. Scan / connection logic

| Element | Value | Status |
|---------|-------|--------|
| Company ID filter | `0x5448`, `0x4254`, `0x5A54` (SDK `FcScanner`, documented in `Reports/FitCloud_SDK_Compatibility_Report.md`) | ✅ (SDK-side; 0x5A54 exact match for P6) |
| Advertised service acceptance | `00003802-0000-1000-8000-00805f9b34fb` (BlueBit `FitCloudProvider.canHandle()` matches the same constant in the app: `Nd/e.java:30–32`) | ✅ |
| Scan-filter literal | `020002fd-3c17-d293-8e48-14fe2e4da212` (exact string; role unknown) | ⚠️ |
| Connect-time GATT check | discovered services contain `00003802`, then char `00004a02` inside it (`Nd/b.java:40–47`; missing ⇒ exception `p147k9.c`) | ✅ code |
| Connector transport (actual) | `01ff` + `ff02`/`ff03` | ✅ code + hardware |

---

## 6. Firmware / OTA (corrected)

| Element | Status |
|---------|--------|
| `Fc#DfuManager` (`Fd/j.java`) exists, distinguished by dfuType | ✅ Confirmed |
| DFU transport | Shared with `Fc#TransportPacket` (same 0xAA/CRC/SN framing; likely long-payload chunking path in `U1/e.b()`) | ✅ framing, ⚠️ DFU specifics |
| DFU opcodes / file layout | ❓ **Unresolved in this pass** (v1's `0x49/0x15/0x004D` opcode claims were unverified and removed) |

---

## 7. Capability matrix (⚠️ inferred, do not rely on without device testing)

| Family | Main transport | Dial | Voice | File transfer | Audio/SPP | GPS-adjacent firmware |
|--------|---------------|------|-------|---------------|-----------|----------------------|
| P6 (FitCloud tracker) | ✅ hw | ✅ hw (`6287` service) | ✅ code, absent on P6 hw | ✅ code, absent on P6 hw | ❌ | ❓ untested |
| BeePro audio | ✅ code | ❌ | ❌ | ❌ | ✅ code | ❌ |

---

## 8. Inspire 3 / Fitbit compatibility

**Absent** — zero static evidence (strings, UUIDs, protocol logic; see `REPORT.md` §10).
FitCloudPro speaks an entirely different ecosystem protocol (Topstep FcProtocolPacket over a
`0xAA`-framed transport with a statically derived AES key).

| Device | FitCloudPro support | BlueBit support | Notes |
|--------|--------------------:|-----------------|-------|
| FitCloud P6 | ✅ native | ✅ SDK-assisted (validated, partial: LCD shape query) | userId binding required |
| Other FitCloud trackers | ⚠️ likely | ❓ untested | Same transport family |
| BeePro audio | ✅ native | ❌ out of scope | SPP/GATT audio path |
| Fitbit Inspire 3 | ❌ **none** | separate investigation | Gattlink/GoldenGate/DTLS |
| Any Fitbit | ❌ **none** | — | Different stack entirely |

---

## 9. Summary

1. The P6 **is** a real FitCloud-family device: its connected GATT matches the SDK's transport
   and dial service constants 1:1, and its advertisement matches the SDK's scan discriminator.
2. The SDK's single device-specific failure on the P6 (`requestLcdShape()`) is a **per-variant
   feature gap**, with the validated fallback of deriving display shape information from
   `requestDialPushInfo()` responses.
3. Authentication is userId-keyed — BlueBit must reuse the official-app binding (`userId
   26544037`) — and the session cryptography is derived, not negotiated, as documented in
   `AUTHENTICATION_ANALYSIS.md`.
4. No Fitbit/Inspire 3 component exists anywhere in the APK.

*End of Device Compatibility Analysis (v2).*
