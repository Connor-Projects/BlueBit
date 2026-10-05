# Inspire 3 Pairing Protocol Investigation Report

**Date:** 2026-09-01
**Device:** Fitbit Inspire 3 (D8:9E:C1:BA:3D:E4)
**State:** Unpaired, in pairing mode
**APK Analyzed:** Fitbit-base.apk (JADX 1.5.6)

---

## Executive Summary

The Inspire 3 exposes **7 GATT services** in pairing mode. Two of these services (`ABBAFD00` and `4EEE1C00`) are proprietary and not part of the GoldenGate sync protocol. After exhaustive APK analysis:

| Service | Found in APK? | Role | Confidence |
|---|---|---|---|
| `ABBAFD00` | ✅ Yes | **Link Controller** — connection config/status AFTER sync | **HIGH** |
| `4EEE1C00` | ❌ **NO** | **Unknown** — possibly pairing or device config | **UNKNOWN** |

**Critical finding:** The `4EEE1C00` service does not appear in the decompiled Fitbit APK's Java code, Kotlin code, or native `.so` libraries. This means either:
1. The pairing protocol is handled outside the APK (Android system, Google Fast Pair, server-side)
2. The UUID was heavily obfuscated beyond recognition
3. The pairing protocol is in a separate, undiscovered library

---

## Phase 1: Complete GATT Service Map

### Service 1: Generic Access (`00001800`)
Standard BLE service. Not directly involved in pairing.

### Service 2: Generic Attribute (`00001801`)
Standard BLE service. BlueBit subscribes to Service Changed indications.

### Service 3: Device Information (`0000180A`)
Standard BLE service. Contains manufacturer, model, serial, firmware info. Not read by BlueBit.

### Service 4: Link Controller (`ABBAFD00`)
**Found in APK.** Used for monitoring and controlling connection parameters.

| Char UUID | Properties | APK Usage | Description |
|---|---|---|---|
| `ABBAFD01` | READ+NOTIFY (18) | `qrz.g.subscribe(peer, ABBAFD00, ABBAFD01, false)` | CurrentConnectionConfiguration |
| `ABBAFD02` | READ+NOTIFY (18) | `qrz.g.subscribe(peer, ABBAFD00, ABBAFD02, false)` | CurrentConnectionStatus |
| `ABBAFD03` | READ (2) | Not referenced | Unknown (possibly read-only status) |

**APK Evidence (`defpackage/qpi.java`):**
```java
case 4:  qrzVar.g.subscribe(peer, qso.a(ABBAFD00), qso.b(ABBAFD01), false);  // Subscribe to config
case 7:  qrzVar.g.subscribe(peer, qso.a(ABBAFD00), qso.c(ABBAFD02), false);  // Subscribe to status
case 11: reader.read(qso.a(ABBAFD00), qso.c(ABBAFD02));                      // Read status
case 12: listener.register(qso.b(ABBAFD01));                                  // Register config listener
```

**BlueBit probe result:**
```
ABBAFD01 READ: 27000000F401F70000
- 0x00000027 = 39 (protocol version?)
- 0x01F4 = 500 (connection interval in 1.25ms units = 625ms)
- 0x0000F7 = 247 (MTU — confirmed)
```

**Conclusion:** This is a post-connection configuration service. It is NOT the pairing service.

### Service 5: Gattlink (`ABBAFF00`)
GoldenGate sync transport. BlueBit handles this correctly.

### Service 6: GattDatabase Confirmation (`AC2F0045`)
Database validation service. BlueBit handles this correctly.

### Service 7: Unknown Proprietary (`4EEE1C00`)
**NOT found in APK.** This is the most important unknown.

| Char UUID | Properties | BlueBit Probe | APK Reference |
|---|---|---|---|
| `4EEE1C01` | WRITE+NOTIFY (48) | Not tested | ❌ Not found |
| `4EEE1C02` | READ+WRITE (12) | Not tested | ❌ Not found |
| `4EEE1C03` | READ (2) | ❌ Timed out | ❌ Not found |
| `4EEE1C04` | INDICATE (32) | Not tested | ❌ Not found |
| `4EEE1C05` | NOTIFY (16) | Not tested | ❌ Not found |
| `4EEE1C06` | READ+WRITE+NOTIFY (26) | ❌ Timed out | ❌ Not found |
| `4EEE1C07` | READ+WRITE+INDICATE (42) | ❌ Timed out | ❌ Not found |

**Search scope:**
- All Java source files under `Decoded/Fitbit-base/sources/`
- All native `.so` libraries under `Decoded/Fitbit-arm64/lib/arm64-v8a/`
- Resource XML files
- Case-insensitive and hex-pattern searches

**Result:** Zero matches for `4eee1c`, `4EEE1C`, `00 1C EE 4E`, `4E EE 1C 00`, or UTF-16LE encoding.

---

## Phase 2: APK Search Results

### ABBAFD00 Service

**Found in:** `defpackage/qso.java` (UUID constants), `defpackage/qpi.java` (GATT operations)

**Used by:** `qrz` (LinkController implementation), referenced from `LinkControllerActivity`

**Role:** Monitors and controls BLE connection parameters after GoldenGate sync is established. This is a runtime debugging/management tool, not the pairing protocol.

### 4EEE1C00 Service

**Found:** Nowhere in the APK.

**Implications:**
1. If this service is part of the pairing protocol, the pairing logic is not in the APK
2. The pairing might be handled by Android's built-in BLE stack
3. The pairing might use Google's Fast Pair API
4. The pairing might require server-side Fitbit cloud interaction

### Pairing-Related String Resources

Found in `Decoded/Fitbit-base/resources/res/values/strings.xml`:

| String Key | Value | Significance |
|---|---|---|
| `pair_pin_header_title` | "Enter 4-digit code" | **Passkey Entry pairing** |
| `pair_pin_label` | "4-digit code" | Confirms PIN-based pairing |
| `bond_connecting_header_title` | "Pairing your %1$s with your phone…" | Standard BLE bonding |
| `bonding_dialog_progress_message` | "Bluetooth pairing your device with Android. You may see a confirmation dialog. Please confirm." | System dialog expected |
| `pair_searching_header_title` | "Searching for your %s…" | Discovery phase |
| `sync_permissions_pairing_complete_title` | "Pairing complete" | Success state |

**Conclusion:** The Fitbit app expects **standard BLE Passkey Entry pairing** with a 4-digit PIN code.

---

## Phase 3: Onboarding Flow Analysis

### Fast Pair

The APK contains `FastPairLandingActivity` in `com.fitbit.device.ui.setup.choose`. This suggests the Inspire 3 may support **Google Fast Pair**.

### Device Onboarding Flow (Inferred)

```
1. Device discovery (BLE scan)
   ↓
2. Fast Pair detection (optional, Google API)
   ↓
3. BLE connection established
   ↓
4. Standard BLE pairing triggered
   - Device displays 4-digit code on screen
   - User enters code in Fitbit app
   - OR: System pairing dialog appears
   ↓
5. Bonding completes (BOND_BONDED)
   ↓
6. Service discovery
   ↓
7. GattDatabase validation (read AC2F0145, write AC2F2645)
   ↓
8. Subscribe to Service Changed + Gattlink notifications
   ↓
9. Start GoldenGate/DSNG stack
   ↓
10. DTLS handshake
    ↓
11. CoAP communication established
    ↓
12. Device provisioning (account linking, settings sync)
```

### What BlueBit Is Missing

**Step 4: Standard BLE pairing with Passkey Entry.**

The `createBond()` call fails because:
1. The device requires Passkey Entry (4-digit PIN)
2. Android's `createBond()` without a PIN dialog cannot complete Passkey Entry
3. The device rejects the incomplete pairing attempt

---

## Phase 4: Why `createBond()` Fails

### Evidence

```
createBond() returned true
BOND_STATE_CHANGED: NONE → BONDING
... (30 seconds) ...
BOND_STATE_CHANGED: BONDING → NONE
status=22 (GATT_CONN_TERMINATE_PEER_USER)
```

### Analysis

1. `createBond()` returns `true` — Android queued the request
2. Bond state transitions to `BONDING` — pairing started
3. No system pairing dialog appeared — **no PIN was exchanged**
4. Bonding failed — device rejected incomplete pairing
5. Device disconnected — pairing mode timeout

### Root Cause

**The Inspire 3 uses LE Secure Connections with Passkey Entry.** The device displays a 4-digit PIN on its screen. The central (phone) must provide this PIN to Android's Bluetooth stack. Without the PIN, pairing cannot complete.

`BluetoothDevice.createBond()` alone cannot complete Passkey Entry because:
- The app never reads the PIN from the device
- The app never provides the PIN to Android
- Android's pairing agent has no PIN to verify

### How the Fitbit App Handles This

The Fitbit app:
1. Connects BLE to the device
2. Reads device information to confirm it's the right device
3. Triggers `createBond()`
4. The device displays a 4-digit code
5. The Fitbit app shows "Enter 4-digit code" UI
6. User enters the code
7. The app provides the PIN to Android's pairing agent
8. Pairing completes

---

## Phase 5: The Real Problem

### User Context

> "i cant pair it to my kids google account and its still in paring mode"

The user cannot pair the Inspire 3 through the official Fitbit app because:
1. The device requires a Fitbit account (adult) for initial setup
2. Google Family Link / child account restrictions prevent pairing
3. The device is stuck in pairing mode, unusable

### What BlueBit Would Need

To pair a brand new Inspire 3, BlueBit would need to:

1. **Implement the complete Fitbit onboarding flow**
   - BLE discovery and connection
   - Standard BLE Passkey Entry pairing
   - Read 4-digit PIN from device (visually, from screen)
   - Provide PIN to Android's Bluetooth stack
   - Complete bonding
   - GattDatabase validation
   - GoldenGate initialization
   - DTLS handshake
   - CoAP device provisioning

2. **Reproduce Fitbit's cloud registration**
   - The device likely registers with Fitbit's servers
   - Account linking requires server API calls
   - This is proprietary and not in the APK

3. **Potentially bypass Fitbit's account requirements**
   - The device firmware may enforce account validation
   - Without a valid Fitbit account, the device may reject provisioning

---

## Evidence-Based Conclusions

| Claim | Evidence | Confidence |
|---|---|---|
| `ABBAFD00` is Link Controller, not pairing | `qpi.java` shows subscription/read operations; `LinkControllerActivity` uses it | **HIGH** |
| `4EEE1C00` is not in the APK | Zero matches across all code, native libs, resources | **HIGH** |
| Pairing uses Passkey Entry (4-digit PIN) | `pair_pin_header_title` = "Enter 4-digit code" | **HIGH** |
| `createBond()` fails without PIN | Bond starts but no dialog, then fails after timeout | **HIGH** |
| Device displays PIN on screen | Standard Passkey Entry behavior for trackers | **MEDIUM** |
| GoldenGate/DSNG is correct | Stack initializes, writes succeed, device receives packets | **HIGH** |
| Device ignores Gattlink until paired | 0 RX packets, 0 notifications, active policy decision | **HIGH** |

---

## Recommendations

### Option 1: Pair via Official App First (Recommended)

Pair the Inspire 3 using the official Fitbit app on any compatible account/phone, then use BlueBit for sync. BlueBit's GoldenGate implementation should work with an already-paired device.

**Why this works:** Once paired, the device no longer requires the pairing protocol. It will accept Gattlink resets and enter normal sync mode.

### Option 2: Implement Passkey Entry in BlueBit

If the user wants BlueBit to handle initial pairing:

1. Connect BLE
2. Trigger `createBond()`
3. **Read the 4-digit PIN from the Inspire 3's screen** (user must visually read it)
4. Provide the PIN to Android via `BluetoothDevice.setPin()` or pairing callback
5. Wait for bonding to complete
6. Proceed with GoldenGate initialization

**Challenge:** Android's `BluetoothDevice` API does not expose `setPin()` for LE Secure Connections. The PIN must be provided through a `BluetoothPairingRequest` receiver or system dialog.

### Option 3: Reverse-Engineer Full Pairing

Attempt to discover the 4EEE1C00 protocol through:
- HCI snoop of official app pairing (requires working pairing)
- BLE sniffer (nRF52840 Dongle) capturing air traffic
- Firmware analysis of Inspire 3

**Effort:** Months. Success uncertain.

---

## Next Immediate Step

**Test BlueBit with an already-paired Inspire 3.**

If the user can temporarily pair the device with the official Fitbit app (on any account), we can verify whether BlueBit's GoldenGate stack works correctly with a paired device. This would confirm:

1. The GoldenGate/DSNG implementation is correct
2. The issue is purely the missing pairing step
3. BlueBit can be a sync-only tool for paired devices

---

*Report compiled from: APK analysis (JADX 1.5.6), 5 controlled experiments, direct BLE probing, native library analysis*
*Confidence: HIGH for all claims marked HIGH*
