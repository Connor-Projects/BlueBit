# Inspire 3 Pairing Mode — Service Probe Results

## Experiment: Read Unknown Services

**Date:** 2026-09-01
**Device:** Inspire 3 in pairing mode (unpaired)
**Method:** Read all readable characteristics from unknown services

## Results

### Service `abbafd00-e56a-484c-b832-8b17cf6cbfe8` (Unknown Proprietary)

| Char UUID | Properties | Read Result |
|---|---|---|
| `abbafd01` | READ+NOTIFY | ✅ `27000000F401F70000` (9 bytes) |
| `abbafd02` | READ+NOTIFY | ❌ Timed out / No response |
| `abbafd03` | READ | ❌ Timed out / No response |

**`abbafd01` data decoded:**
```
27000000 F401 F70000
|_______| |__| |____|
  39 LE   500  247 LE
  uint32  uint16 uint24
```

- **39** (uint32 LE) — Likely protocol version or capability flags
- **500** (uint16 LE) — Likely max connection interval or buffer size
- **247** (uint24 LE) — **Confirmed: Current MTU size** (matches negotiated MTU=247)

### Service `4eee1c00-4133-479b-8663-02c84bdc14be` (Unknown Proprietary)

| Char UUID | Properties | Read Result |
|---|---|---|
| `4eee1c03` | READ | ❌ Timed out / No response |
| `4eee1c06` | READ+WRITE+NOTIFY | ❌ Timed out / No response |
| `4eee1c07` | READ+WRITE+INDICATE | ❌ Timed out / No response |

## Key Observations

1. **`abbafd01` responds with device parameters** — this is an active service, not just a placeholder
2. **Reads are sequential but only the first succeeds** — suggests the device expects a specific interaction pattern
3. **Device disconnects after ~28 seconds** — consistent pairing mode timeout
4. **No response from `4eee1c00` service** — may require write/subscription before reading

## Hypothesis: `abbafd00` is a Parameter/Control Service

The data from `abbafd01` looks like connection/device parameters:
- Version/identifier: 39
- Max connection interval: 500 (units of 1.25ms = 625ms)
- MTU: 247 bytes

This service might be used to:
1. Read device capabilities/parameters
2. Configure connection parameters
3. Signal pairing state/progress

## Hypothesis: `4eee1c00` Requires Write-First Protocol

Characteristics with WRITE+NOTIFY/INDICATE properties suggest this service expects:
1. Client writes a command/request
2. Device responds via notification/indication
3. The read-only characteristics may only be accessible after certain commands

## Next Steps

1. **Subscribe to notifications** on `abbafd01` and `abbafd02` before reading
2. **Write probe commands** to `4eee1c02` (WRITE) to see if it triggers responses
3. **Capture Fitbit app pairing flow** via HCI snoop to see exact sequence
4. **Try writing to `abbafd03`** (READ only — but may accept writes?)

## Device Behavior Pattern

```
Connect BLE
  ↓
Device accepts connection
  ↓
Services discovered (7 services)
  ↓
Read abbafd01 → Returns parameters (9 bytes)
  ↓
[Device expects more pairing protocol steps]
  ↓
~28 seconds pass
  ↓
Device disconnects (status=22)
```

The device is actively waiting for a pairing protocol handshake that BlueBit hasn't implemented.

---

*Probe date: 2026-09-01*
*Device: Inspire 3 (unpaired, pairing mode)*
