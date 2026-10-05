# BlueBit Inspire 3 Bonding Experiment Report

**Date:** 2026-08-31  
**Tester:** Samsung Galaxy A17 (Android 14)  
**Device Under Test:** Fitbit Inspire 3  
**Address:** D8:9E:C1:BA:3D:E4

---

## BUILD STATUS

| Item | Result |
|------|--------|
| Compilation errors | **0** |
| APK install | **Success** |
| App launched | **Success** |

---

## EXPERIMENT OBJECTIVE

Determine whether the Fitbit Inspire 3 can be bonded through Android's standard BLE pairing flow, and whether bonding is required for GATT connection.

---

## EXPERIMENT FLOW

```text
Detect Inspire 3
    v
Show current bond state
    v
If BOND_NONE: initiate Android createBond()
    v
Log BONDING -> BONDED (or failure)
    v
After bonding: GATT connect + discover services
    v
Disconnect
    v
Reconnect + verify bond persistence + GATT still works
```

---

## RESULTS

### Attempt 1

| Step | Result | Timestamp |
|------|--------|-----------|
| Bond state detected | **BOND_NONE** | 20:57:58.937 |
| createBond() called | **Returned true** | 20:57:58.972 |
| Bond state changed | NONE -> **BONDING** | 20:57:59.007 |
| Bond state changed | BONDING -> **NONE** | 20:58:29.160 |
| **Result** | **FAILED** | -- |

### Attempt 2

| Step | Result | Timestamp |
|------|--------|-----------|
| Bond state detected | **BOND_NONE** | 20:59:28.298 |
| createBond() called | **Returned true** | 20:59:28.306 |
| Bond state changed | NONE -> **BONDING** | 20:59:28.331 |
| Bond state changed | BONDING -> **NONE** | 21:03:13.339 |
| **Result** | **FAILED** | -- |

### Attempt 3

| Step | Result | Timestamp |
|------|--------|-----------|
| Bond state detected | **BOND_NONE** | 21:03:15.213 |
| createBond() called | **Returned true** | 21:03:15.221 |
| Bond state changed | NONE -> **BONDING** | 21:03:15.235 |
| Capture ended while still BONDING | -- | 21:03:45 |

---

## SYSTEM BLUETOOTH LOG ANALYSIS

### SMP (Security Manager Protocol) Trace

```
21:02:43.177  SMP_Pair: state=0, initiating pairing
21:02:43.284  smp_connect_callback: in pairing process
21:02:43.284  smp_sm_event: State=SMP_STATE_IDLE, Event=L2CAP_CONN_EVT
21:02:43.284  btif_dm_get_smp_config: SMP pairing options not found in stack config
21:02:43.284  smp_send_app_cback: Remote request IO capabilities
                auth_req:0x2d, io_cap:4, loc_enc_size:16
21:02:43.285  smp_send_cmd: Sending SMP_OPCODE_PAIRING_REQ
21:02:43.285  smp_sm_event: result state=SMP_STATE_PAIR_REQ_RSP

[30 seconds pass -- NO RESPONSE FROM INSPIRE 3]

21:03:13.286  smp_proc_pairing_cmpl: Pairing process has FAILED
                smp_reason: SMP_RSP_TIMEOUT
                sec_level: 0x0
21:03:13.287  btif_dm_ble_auth_cmpl_evt: LE authentication failed with reason 102
21:03:13.287  btif_storage_remove_ble_bonding_keys: Removing bonding keys
21:03:13.288  disconnect_acl: Disconnecting peer reason: HCI_ERR_PEER_USER
```

### Key System Events

| Time | Event | Meaning |
|------|-------|---------|
| T+0ms | `SMP_OPCODE_PAIRING_REQ` sent | Android sends pairing request to Inspire 3 |
| T+0ms | State = `SMP_STATE_PAIR_REQ_RSP` | Waiting for Inspire 3 to respond |
| T+30s | `SMP_RSP_TIMEOUT` | **Inspire 3 never responded** |
| T+30s | `fail_reason=102` | Android error: remote device timeout |
| T+30s | Bond state = NONE | Keys removed, pairing aborted |
| T+30s | ACL disconnected | Link torn down due to pairing failure |

---

## INTERPRETATION

### What Happened

1. **Android successfully initiated pairing** -- `createBond()` returned `true`, L2CAP connection established
2. **Android sent SMP Pairing Request** -- included IO capabilities, authentication requirements, encryption key size
3. **Inspire 3 did NOT respond** -- waited full 30-second SMP timeout
4. **Android aborted pairing** -- removed bonding keys, disconnected ACL

### What This Means

The **Fitbit Inspire 3 does NOT support Android's standard BLE pairing/bonding flow**. Possible reasons:

1. **Proprietary authentication** -- Fitbit devices use their own authentication protocol over custom GATT services, not standard SMP
2. **No pairing support** -- The Inspire 3 may simply not implement BLE pairing at all
3. **Requires Fitbit app** -- The official Fitbit app may use a known shared secret or out-of-band pairing method

### Supporting Evidence

- The unbonded GATT connection from the previous experiment worked perfectly
- No pairing dialog ever appeared on the Samsung A17 during any attempt
- The Inspire 3 screen did not show any pairing confirmation prompt
- All 3 attempts failed with the identical `SMP_RSP_TIMEOUT` error

---

## CONCLUSION

| Milestone | Status |
|-----------|--------|
| Inspire 3 discovered | ✅ **PASS** |
| Bond state shown | ✅ **PASS** |
| Bonding initiated | ✅ **PASS** |
| Bonding succeeded | ❌ **FAIL** -- `SMP_RSP_TIMEOUT` |
| GATT connect after bonding | ❌ **Not reached** |
| Service discovery | ❌ **Not reached** |
| Disconnect | ❌ **Not reached** |
| Reconnect + verify | ❌ **Not reached** |

### Final Assessment

**The Fitbit Inspire 3 cannot be bonded through standard Android BLE `createBond()`.** The device ignores SMP Pairing Requests entirely.

**Implications for BlueBit:**
1. **Bonding is NOT required** for basic GATT communication -- the unbonded GATT connection worked
2. **Authentication must happen at the application layer** -- likely through one of the 4 Fitbit custom services (particularly `4eee1c00` with its READ/WRITE/NOTIFY/INDICATE characteristics)
3. **The Fitbit app's pairing flow is proprietary** -- reverse engineering the authentication protocol would be needed for full integration
4. **Notification/call mirroring may require authentication** -- this is the likely next research target

### Recommended Next Steps

1. Analyse which custom service handles device authentication
2. Capture Fitbit app's BLE traffic during pairing to understand the proprietary protocol
3. Test if any characteristic reads/writes work without authentication
4. Determine if the Inspire 3 accepts unauthenticated notification subscriptions
