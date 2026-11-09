# FitCloud P6 GATT Discovery Report

**Date:** 2026-10-29  
**Device:** P6_  
**MAC Address:** 90:EB:99:D6:5F:99  
**Bond State:** NONE (unpaired)  
**Connection:** Raw GATT connect, no bonding, no authentication  
**Diagnostic:** `GattDiscoveryDiagnostic.connectAndDiscover()` (read-only)  
**Report File:** `p6_gatt_dump_20261029_222023.txt` (also `p6_gatt_dump_20261029_221935.txt`)

---

## Summary

The P6 connected successfully via raw GATT without prior bonding. Service discovery returned **5 services**. Critically, the advertised service UUID `0x3802` (`00003802-0000-1000-8000-00805f9B34FB`) does **not** appear in the connected GATT database — it is advertisement-only (or only exposed after bonding/pairing).

---

## Complete GATT Service Map

### Service [0]: `00001800-0000-1000-8000-00805f9b34fb` (Generic Access) — PRIMARY

| Char. UUID | Properties | Permissions | Descriptors |
|------------|------------|-------------|-------------|
| `00002a00` (Device Name) | READ | NONE | — |
| `00002a01` (Appearance) | READ | NONE | — |

### Service [1]: `0000ff12-0000-1000-8000-00805f9b34fb` — PRIMARY

| Char. UUID | Properties | Permissions | Descriptors |
|------------|------------|-------------|-------------|
| `0000ff13` | WRITE | NONE | — |
| `0000ff14` | NOTIFY | NONE | `00002902` (CCCD) |
| `0000ff15` | READ, WRITE_NO_RESPONSE | NONE | — |

### Service [2]: `000001ff-3c17-d293-8e48-14fe2e4da212` — PRIMARY

| Char. UUID | Properties | Permissions | Descriptors |
|------------|------------|-------------|-------------|
| `0000ff03` | READ, WRITE_NO_RESPONSE, NOTIFY | NONE | `00002902` (CCCD) |
| `0000ff02` | WRITE | NONE | — |

### Service [3]: `0000d0ff-3c17-d293-8e48-14fe2e4da212` — PRIMARY

| Char. UUID | Properties | Permissions | Descriptors |
|------------|------------|-------------|-------------|
| `0000ffd1` | WRITE_NO_RESPONSE | NONE | — |
| `0000ffd2` | READ | NONE | — |
| `0000ffd3` | READ | NONE | — |
| `0000ffd4` | READ | NONE | — |
| `0000ffd5` | READ | NONE | — |
| `0000ffd8` | READ | NONE | — |
| `0000fff1` | READ | NONE | — |
| `0000fff2` | READ | NONE | — |
| `0000ffe0` | READ | NONE | — |
| `0000fff3` | READ | NONE | — |

### Service [4]: `00006287-3c17-d293-8e48-14fe2e4da212` — PRIMARY

| Char. UUID | Properties | Permissions | Descriptors |
|------------|------------|-------------|-------------|
| `00006387` | WRITE_NO_RESPONSE | NONE | — |
| `00006487` | WRITE, NOTIFY | NONE | `00002902` (CCCD) |

---

## Key Observations

1. **`0x3802` not present in connected GATT:** The advertised service `00003802-...` does not appear among the 5 discovered services. It exists only in the BLE advertisement (scan filter match) or may appear only after bonding.

2. **No characteristic requires encryption:** All permissions are `NONE(0x0)` — no `READ_ENCRYPTED`, `WRITE_ENCRYPTED`, etc. This means raw GATT reads/writes are possible without bonding.

3. **Notify-capable characteristics:**
   - `0000ff14` (service `ff12`) — NOTIFY + CCCD
   - `0000ff03` (service `01ff-...`) — NOTIFY + CCCD
   - `00006487` (service `6287-...`) — NOTIFY + CCCD

4. **Write capabilities:** Multiple WRITE / WRITE_NO_RESPONSE characteristics across services `ff12`, `01ff-...`, `d0ff-...`, `6287-...`.

5. **All characteristics use standard CCCD `0x2902`** for notification configuration where applicable.

---

## Connection Log (logcat, tag `BlueBitP6Gatt`)

```
P6 GATT DISCOVERY
P6_SELECTED
DEVICE_NAME=P6_
DEVICE_ADDRESS=90:EB:99:D6:5F:99
BOND_STATE=NONE
GATT_CONNECT_START
GATT_CONNECTION_STATE status=0 newState=CONNECTED
GATT_SERVICES_DISCOVERY_START
GATT_SERVICES_DISCOVERED status=0
GATT_REPORT_WRITTEN: .../Documents/p6_gatt_dump_20261029_222023.txt
========== DEVICE GATT DATABASE ==========
(5 services enumerated read-only)
========== END DEVICE GATT DATABASE ==========
GATT_DISCONNECT: calling disconnect()
GATT_DISCONNECT: closed
```

---

## Files

- `Reports/FitCloud_P6_GATT_Discovery.md` — this report
- `Reports/p6_gatt_dump_20261029_221935.txt` — raw logcat-based dump
- `Reports/p6_gatt_dump_20261029_222023.txt` — structured report (above)

---

## Implications for SDK Compatibility

- The SDK `FcScanner` accepts companyId `0x5A54` (P6 matches) — **scan-level match confirmed**.
- The SDK's internal GATT protocol may target specific services/characteristics. Since `0x3802` is not a connected service, the SDK likely communicates through one of the custom services above (`ff12`, `01ff-...`, `d0ff-...`, `6287-...`) — but this is **not confirmed** without testing the SDK connection.
- **Next step:** Phase 3 — SDK connection test using `FcConnector.connect(bindOrLogin=false)`.
