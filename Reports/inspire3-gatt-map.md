# Inspire 3 GATT Service Map (Pairing Mode)

**Device:** Fitbit Inspire 3 (D8:9E:C1:BA:3D:E4)
**State:** Unpaired, in pairing mode
**Date:** 2026-09-01
**MTU:** 247

---

## Complete Service Listing

### Service 1: Generic Access (`00001800-0000-1000-8000-00805f9b34fb`)

| Char UUID | Properties | Permissions | Value Read |
|---|---|---|---|
| `00002A00` | READ (2) | — | Not read |
| `00002A01` | READ (2) | — | Not read |
| `00002A04` | READ (2) | — | Not read |
| `00002AA6` | READ (2) | — | Not read |

### Service 2: Generic Attribute (`00001801-0000-1000-8000-00805f9b34fb`)

| Char UUID | Properties | Permissions | Descriptors | Value Read |
|---|---|---|---|---|
| `00002A05` | INDICATE (32) | — | CCC `00002902` | Subscribed by BlueBit |

### Service 3: Device Information (`0000180A-0000-1000-8000-00805f9b34fb`)

| Char UUID | Properties | Name (Standard) | Value Read |
|---|---|---|---|
| `00002A29` | READ (2) | Manufacturer Name | Not read |
| `00002A24` | READ (2) | Model Number | Not read |
| `00002A25` | READ (2) | Serial Number | Not read |
| `00002A27` | READ (2) | Hardware Revision | Not read |
| `00002A26` | READ (2) | Firmware Revision | Not read |
| `00002A28` | READ (2) | Software Revision | Not read |
| `00002A23` | READ (2) | System ID | Not read |
| `00002A2A` | READ (2) | PnP ID | Not read |
| `00002A50` | READ (2) | PnP ID (alt) | Not read |

### Service 4: Device Parameters/Control (`ABBAFD00-E56A-484C-B832-8B17CF6CBFE8`)

| Char UUID | Properties | Permissions | Descriptors | Value Read |
|---|---|---|---|---|
| `ABBAFD01` | READ (2) + NOTIFY (16) = 18 | — | CCC `00002902` | ✅ `27000000F401F70000` |
| `ABBAFD02` | READ (2) + NOTIFY (16) = 18 | — | CCC `00002902` | ❌ Timed out |
| `ABBAFD03` | READ (2) | — | — | ❌ Timed out |

**ABBAFD01 Decoded Value:**
```
Bytes:  27 00 00 00 F4 01 F7 00 00
Field1: 0x00000027 = 39       (uint32 LE) — Protocol version / capability flags
Field2: 0x01F4 = 500          (uint16 LE) — Connection interval / buffer size
Field3: 0x0000F7 = 247        (uint24 LE) — MTU size (confirmed)
```

### Service 5: Gattlink (`ABBAFF00-E56A-484C-B832-8B17CF6CBFE8`)

| Char UUID | Properties | Permissions | Descriptors | BlueBit Usage |
|---|---|---|---|---|
| `ABBAFF01` | WRITE (4) | — | — | Gattlink TX (reset packets) |
| `ABBAFF02` | NOTIFY (16) | — | CCC `00002902` | Gattlink RX (subscribed, no data) |

### Service 6: GattDatabase Confirmation (`AC2F0045-8182-4BE5-91E0-2992E6B40EBB`)

| Char UUID | Properties | Permissions | Value Read |
|---|---|---|---|
| `AC2F0145` | READ (2) | — | ✅ Ephemeral pointer `AC2F2645...` |
| `AC2F2645` | WRITE (8) | — | ✅ Written `0x00` by BlueBit |

### Service 7: Pairing Protocol (`4EEE1C00-4133-479B-8663-02C84BDC14BE`)

| Char UUID | Properties | Permissions | Descriptors | Value Read |
|---|---|---|---|---|
| `4EEE1C01` | WRITE (8) + NOTIFY (16) + READ? (32?) = 48? | — | CCC `00002902` | Not read (WRITE/NOTIFY) |
| `4EEE1C02` | READ (2) + WRITE (8) = 12 | — | — | Not read |
| `4EEE1C03` | READ (2) | — | — | ❌ Timed out |
| `4EEE1C04` | INDICATE (32) | — | CCC `00002902` | Not read |
| `4EEE1C05` | NOTIFY (16) | — | CCC `00002902` | Not read |
| `4EEE1C06` | READ (2) + WRITE (8) + NOTIFY (16) = 26 | — | CCC `00002902` | ❌ Timed out |
| `4EEE1C07` | READ (2) + WRITE (8) + INDICATE (32) = 42 | — | CCC `00002902` | ❌ Timed out |

**Note on Properties:** The properties bitmask is additive:
- 2 = READ
- 4 = WRITE_NO_RESPONSE
- 8 = WRITE
- 16 = NOTIFY
- 32 = INDICATE

---

## Key Observations

1. **Service 4 (ABBAFD00)** has 2 NOTIFY characteristics + 1 READ. ABBAFD01 actively returns parameters. This is likely a control/status service.

2. **Service 7 (4EEE1C00)** has 7 characteristics with multiple CCC descriptors. This is the most complex service and is almost certainly the pairing protocol service.

3. **Sequential read behavior:** Only ABBAFD01 responded to a read. Other READ characteristics timed out, suggesting the device expects a specific initialization sequence before allowing further reads.

4. **All NOTIFY/INDICATE characteristics have CCC descriptors** — the device expects the client to subscribe.

5. **Device disconnects after ~28 seconds** if the pairing protocol is not satisfied.

---

## Property Bitmask Reference

| Value | Property |
|---|---|
| 0x01 | BROADCAST |
| 0x02 | READ |
| 0x04 | WRITE_NO_RESPONSE |
| 0x08 | WRITE |
| 0x10 | NOTIFY |
| 0x20 | INDICATE |
| 0x40 | AUTHENTICATED_SIGNED_WRITES |
| 0x80 | EXTENDED_PROPERTIES |

---

*Map compiled from direct BLE discovery on 2026-09-01*
