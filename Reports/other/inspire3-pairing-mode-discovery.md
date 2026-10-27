# Inspire 3 Pairing Mode Discovery

## Critical Finding

The Inspire 3 is **brand new, unpaired** — in pairing mode. When in pairing mode, it exposes **7 GATT services**, not the 3 that BlueBit handles.

## Services Discovered

| # | Service UUID | Type | Characteristics | BlueBit Handles? |
|---|---|---|---|---|
| 1 | `00001800-0000-1000-8000-00805f9b34fb` | Generic Access | 4 (Device Name, Appearance, PPCP, Central Address Resolution) | ❌ No |
| 2 | `00001801-0000-1000-8000-00805f9b34fb` | Generic Attribute | 1 (Service Changed) + CCC | ✅ Yes |
| 3 | `0000180a-0000-1000-8000-00805f9b34fb` | Device Information | 8 (Manufacturer, Model, Serial, HW Rev, SW Rev, FW Rev, System ID, PnP ID) | ❌ No |
| 4 | `abbafd00-e56a-484c-b832-8b17cf6cbfe8` | **Unknown proprietary** | 3 chars (fd01: READ+NOTIFY, fd02: READ+NOTIFY, fd03: READ) | ❌ No |
| 5 | `abbaff00-e56a-484c-b832-8b17cf6cbfe8` | Gattlink | 2 chars (aff01: WRITE, aff02: NOTIFY) + CCC | ✅ Yes |
| 6 | `ac2f0045-8182-4be5-91e0-2992e6b40ebb` | GattDatabaseConfirmation | 2 chars (0145: READ, 2645: WRITE) | ✅ Yes |
| 7 | `4eee1c00-4133-479b-8663-02c84bdc14be` | **Unknown proprietary** | 7 chars + multiple CCCs | ❌ No |

## Key Observations

### Service 7: `4eee1c00` - Likely Pairing Service

This is a complex service with 7 characteristics:

| Char UUID | Properties | Description |
|---|---|---|
| `4eee1c01` | 48 (WRITE+NOTIFY) | Likely pairing command input |
| `4eee1c02` | 12 (READ+WRITE) | Likely pairing status/config |
| `4eee1c03` | 2 (READ) | Likely read-only info |
| `4eee1c04` | 32 (INDICATE) | Likely pairing response/indication |
| `4eee1c05` | 16 (NOTIFY) | Likely notification stream |
| `4eee1c06` | 26 (READ+WRITE+NOTIFY) | Likely bidirectional pairing data |
| `4eee1c07` | 42 (READ+WRITE+INDICATE) | Likely bidirectional pairing data |

**All characteristics have CCC descriptors** — the device expects subscriptions to multiple characteristics.

### Service 4: `abbafd00` - Possibly Authentication/Status Service

3 characteristics:
- `abbafd01`: READ+NOTIFY
- `abbafd02`: READ+NOTIFY
- `abbafd03`: READ

## Evidence

1. **UUID `4eee1c00` does NOT appear in the Fitbit APK's GoldenGate code.** This means it's handled by the device onboarding/setup layer, not the sync layer.
2. **UUID `abbafd00` also does NOT appear in GoldenGate code.** Same conclusion.
3. **The device is in pairing mode** (BOND_NONE, no prior pairing).
4. **Gattlink resets are ignored** — the device is waiting for pairing protocol completion.

## Hypothesis: Pairing Sequence

Based on the service layout and Fitbit's architecture:

```
1. Connect BLE
2. Read Device Information (service 180a) to identify device type
3. Interact with service 4eee1c00 (pairing service):
   a. Subscribe to notifications/indications
   b. Read characteristic values
   c. Write pairing commands
   d. Exchange pairing codes
4. Interact with service abbafd00 (possibly authentication/status):
   a. Subscribe to notifications
   b. Read status
5. Device transitions from pairing mode to paired mode
6. Gattlink (service abbaff00) becomes responsive
7. GoldenGate/DTLS sync can proceed
```

## Next Steps

1. **Capture Fitbit app pairing flow via HCI snoop** when setting up a new Inspire 3
2. **Reverse-engineer the pairing protocol** for services 4 and 7
3. **Implement the pairing sequence** in BlueBit before attempting GoldenGate

## Why This Explains Everything

- ❌ WRITE_TYPE didn't matter — device wasn't even looking at Gattlink yet
- ❌ Bonding failed — device requires pairing protocol first, not raw BLE bonding
- ❌ Device "ignored" packets — it was waiting for pairing service interaction
- ✅ GattDatabase validation worked — that's part of the setup, not the pairing
- ✅ Service discovery worked — device exposes all services in pairing mode

---

*Discovery date: 2026-09-01*
*Device: Inspire 3 in pairing mode (unpaired)*
