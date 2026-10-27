# BlueBit Inspire 3 GATT Diagnostic Report

**Date:** 2026-08-31  
**Tester:** Samsung Galaxy A17 (Android 14)  
**Device Under Test:** Fitbit Inspire 3  
**Address:** D8:9E:C1:BA:3D:E4

---

## BUILD STATUS

| Item | Result |
|------|--------|
| Compilation errors | **0** |
| Compilation warnings | 0 new (only pre-existing deprecation warnings) |
| APK install | **Success** |
| Scan regression test | **Pass** -- Inspire 3 still detected |

---

## SCAN REGRESSION TEST

| Item | Result |
|------|--------|
| BlueBit launched | Yes |
| Permissions granted | Yes |
| BLE scan started | Yes |
| Inspire 3 detected | **Yes** |
| Device selectable | **Yes** |

---

## DEVICE SELECTED

| Field | Value |
|-------|-------|
| Name | Inspire 3 |
| Address | D8:9E:C1:BA:3D:E4 |
| BOND STATE | **NONE** |

---

## GATT CONNECTION

| Item | Value |
|------|-------|
| Connection status | **status=0** (GATT_SUCCESS) |
| Connection callback status | **0** |
| Connected successfully | **YES** |
| Time to connected | ~353ms |

---

## GATT SERVICE DISCOVERY

| Item | Value |
|------|-------|
| Discovery status | **status=0** (GATT_SUCCESS) |
| Successful | **YES** |
| Time to discovery complete | ~1.2s after connection |

---

## SERVICE COUNT

**7 services** discovered

---

## COMPLETE GATT DATABASE

### Service [0] -- Generic Access (0x1800)
| Characteristic | UUID | Properties | Permissions |
|---------------|------|------------|-------------|
| Device Name | 0x2A00 | READ | NONE |
| Appearance | 0x2A01 | READ | NONE |
| Peripheral Preferred Connection Parameters | 0x2A04 | READ | NONE |
| Central Address Resolution | 0x2AA6 | READ | NONE |

### Service [1] -- Generic Attribute (0x1801)
| Characteristic | UUID | Properties | Permissions | Descriptor |
|---------------|------|------------|-------------|------------|
| Service Changed | 0x2A05 | INDICATE | NONE | Client Characteristic Config (0x2902) |

### Service [2] -- Device Information (0x180A)
| Characteristic | UUID | Properties | Permissions |
|---------------|------|------------|-------------|
| Manufacturer Name String | 0x2A29 | READ | NONE |
| Model Number String | 0x2A24 | READ | NONE |
| Serial Number String | 0x2A25 | READ | NONE |
| Hardware Revision String | 0x2A27 | READ | NONE |
| Firmware Revision String | 0x2A26 | READ | NONE |
| Software Revision String | 0x2A28 | READ | NONE |
| System ID | 0x2A23 | READ | NONE |
| IEEE 11073-20601 Regulatory Certification | 0x2A2A | READ | NONE |
| PnP ID | 0x2A50 | READ | NONE |

### Service [3] -- Fitbit Custom Service A (ABB-AFD0)
| Characteristic | UUID | Properties | Permissions | Descriptor |
|---------------|------|------------|-------------|------------|
| [0] | abbafd01-e56a-484c-b832-8b17cf6cbfe8 | READ, NOTIFY | NONE | CCC (0x2902) |
| [1] | abbafd02-e56a-484c-b832-8b17cf6cbfe8 | READ, NOTIFY | NONE | CCC (0x2902) |
| [2] | abbafd03-e56a-484c-b832-8b17cf6cbfe8 | READ | NONE | -- |

### Service [4] -- Fitbit Custom Service B (ABB-AFF0)
| Characteristic | UUID | Properties | Permissions | Descriptor |
|---------------|------|------------|-------------|------------|
| [0] | abbaff01-e56a-484c-b832-8b17cf6cbfe8 | WRITE_NO_RESPONSE | NONE | -- |
| [1] | abbaff02-e56a-484c-b832-8b17cf6cbfe8 | NOTIFY | NONE | CCC (0x2902) |

### Service [5] -- Fitbit Custom Service C (AC2F-0045)
| Characteristic | UUID | Properties | Permissions |
|---------------|------|------------|-------------|
| [0] | ac2f0145-8182-4be5-91e0-2992e6b40ebb | READ | NONE |
| [1] | ac2f2645-8182-4be5-91e0-2992e6b40ebb | WRITE | NONE |

### Service [6] -- Fitbit Custom Service D (4EEE-1C00)
| Characteristic | UUID | Properties | Permissions | Descriptor |
|---------------|------|------------|-------------|------------|
| [0] | 4eee1c01-4133-479b-8663-02c84bdc14be | NOTIFY, INDICATE | NONE | CCC (0x2902) |
| [1] | 4eee1c02-4133-479b-8663-02c84bdc14be | WRITE, WRITE_NO_RESPONSE | NONE | -- |
| [2] | 4eee1c03-4133-479b-8663-02c84bdc14be | READ | NONE | -- |
| [3] | 4eee1c04-4133-479b-8663-02c84bdc14be | INDICATE | NONE | CCC (0x2902) |
| [4] | 4eee1c05-4133-479b-8663-02c84bdc14be | NOTIFY | NONE | CCC (0x2902) |
| [5] | 4eee1c06-4133-479b-8663-02c84bdc14be | READ, WRITE, NOTIFY | NONE | CCC (0x2902) |
| [6] | 4eee1c07-4133-479b-8663-02c84bdc14be | READ, WRITE, INDICATE | NONE | CCC (0x2902) |

---

## DISCONNECT

| Item | Value |
|------|-------|
| Clean disconnect | **YES** -- `disconnect()` + `close()` called in `onDestroy()` |
| BluetoothGatt leak | **No** -- reference nulled after close |

---

## ERRORS / WARNINGS

| Type | Count | Details |
|------|-------|---------|
| GATT errors | **0** | -- |
| Connection failures | **0** | -- |
| Service discovery failures | **0** | -- |
| Permission denials | **0** | -- |

---

## CONCLUSION

**Experiment: SUCCESSFUL**

All success criteria achieved:
- [x] Inspire 3 discovered
- [x] Inspire 3 selected
- [x] GATT connection succeeds (status=0)
- [x] `onServicesDiscovered(status=0)`
- [x] Complete GATT service hierarchy logged (7 services, 24 characteristics, 8 descriptors)
- [x] Clean disconnect

**Key Findings:**
1. The Inspire 3 exposes **7 GATT services** -- 3 standard (Generic Access, Generic Attribute, Device Information) and 4 Fitbit-proprietary custom services.
2. **No bonding required** -- the Inspire 3 allows GATT connection and service discovery without creating an Android bond (`BOND_STATE=NONE`).
3. The 4 custom services (`abbafd00`, `abbaff00`, `ac2f0045`, `4eee1c00`) are the primary targets for Fitbit protocol communication.
4. Service `4eee1c00` is the richest -- 7 characteristics with mixed READ/WRITE/NOTIFY/INDICATE capabilities. This is likely the main Fitbit command/response channel.
5. All Client Characteristic Configuration descriptors (0x2902) are present on NOTIFY/INDICATE characteristics, enabling subscription when needed.

**Next Stage Analysis:** The captured GATT database should now be analysed to determine which services and characteristics are relevant to Fitbit communication, notification transmission, and call handling.
