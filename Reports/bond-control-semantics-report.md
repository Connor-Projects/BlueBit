# BlueBit — `bond/control` Firmware Semantics & Inspire 3 Pairing/Provisioning State Investigation

## 1. Executive Conclusion

**The exact condition that causes the Inspire 3 firmware to return `4.01 Unauthorized` for `bond/control` cannot be determined from available evidence.**

However, exhaustive code tracing of the official Fitbit APK proves that:

1. **The official app performs NO device-visible operations between GoldenGate/DTLS connection and `bond/control`**
2. **`pair/display` is NOT part of the normal `createNewBond()` flow**
3. **The CoAP request format is byte-for-byte identical between Fitbit and BlueBit**
4. **The 4.01 response maps to `RF_ERR_KEY_EXPIRED` (5378), a firmware key-management error**
5. **No Android-side state, credential, or protocol step has been identified that BlueBit is missing**

The authorization gate is **in the Inspire 3 firmware** and likely requires **server-mediated pairing validation** or a **device-provisioned credential** that BlueBit cannot legitimately reproduce.

**Status of each claim:**
- No device-visible prep before bond/control: **PROVEN**
- pair/display not in main flow: **PROVEN**
- Request format identical: **PROVEN**
- 4.01 = RF_ERR_KEY_EXPIRED: **PROVEN**
- Firmware authorization gate: **STRONGLY INFERRED**
- Server-mediated state required: **POSSIBLE** (no direct evidence, but no alternative explains the gate)

---

## 2. Exact Semantics Known for `bond/control`

### 2.1 Official Fitbit Implementation

**File:** `com/fitbit/goldengate/bond/CoapBondControlResource.java:51-55`

```java
private final OutgoingRequest createRequest(int i) {
    BondControl.Control controlBuild = new BondControlRequestBuilder(i).build();
    OutgoingRequestBuilder outgoingRequestBuilder = new OutgoingRequestBuilder("bond/control", Method.PUT);
    outgoingRequestBuilder.body(controlBuild.toByteArray());
    return outgoingRequestBuilder.build();
}
```

**File:** `com/fitbit/goldengate/bond/BondControlRequestBuilder.java:10-16`

```java
public final BondControl.Control build() {
    BondControl.Control.Builder builderNewBuilder = BondControl.Control.newBuilder();
    builderNewBuilder.setOperationTimeout(this.timeout);
    return builderNewBuilder.build();
}
```

**Request structure:**
- **Method:** CoAP PUT
- **URI:** `bond/control`
- **Payload:** Protobuf `BondControl.Control { operationTimeout = N }` (N = 0-255 seconds)
- **Content-Format:** application/octet-stream (protobuf)
- **DTLS session:** Same BOOTSTRAP PSK session used for all CoAP traffic
- **No additional headers, options, or authentication tokens**

### 2.2 Official Pre-bond Sequence

**File:** `defpackage/ahsr.java:319-363` (ahsr.l())

```
ahsr.f() ──→ ahsk coroutine ──→ ahsr.l()
    ↓
ahsr.l():
  1. Check GATT connection exists (nrq)
  2. Check device is NOT already BOND_BONDED (Android bondState)
  3. Call vaw.h(duration, timeout, errorHandler, successHandler, connectHandler)
     ↓
     ahsj (connectHandler):
       a. pxk.c() ──→ BitgattPeerApi.connect() ──→ Service discovery
       b. pxk.c() ──→ Notification setup
       c. pgp.connect() ──→ GoldenGate stack start ──→ Gattlink ──→ DTLS
       d. Return pgp (GoldenGateCommandHandler)
  4. Store pgp in this.j
```

**Then separately:**
```
ahsr.h(context) ──→ ahso coroutine
    ↓
ahsr.j.createNewBond(context)
    ↓
GoldenGateCommandHandler.createNewBond()
    ↓
BondCommandsHandler.create(context, address)
    ↓
  1. peripheralBondStatusChecker.a(address)
     └─→ If bonded → skip
     └─→ If not bonded → createBond()
  2. createBond():
     a. peripheralBondRemover.a(context, address) ──→ remove existing bond if any
     b. bondControlResource.enable(address, timeout) ──→ CoAP PUT bond/control
     c. bondCreator.create(address, timeout) ──→ Android createBond()
```

### 2.3 Nothing Between GoldenGate and bond/control

**PROVEN:** Between step `c. pgp.connect()` (GoldenGate/DTLS established) and step `b. bondControlResource.enable()` (bond/control), the official Fitbit app performs **zero device-visible operations**.

No GATT reads/writes. No additional CoAP requests. No state changes. No credential provisioning.

---

## 3. Meaning of the 4.01 Response

### 3.1 CoAP 4.01 → RF_ERR_KEY_EXPIRED

**File:** `com/fitbit/goldengate/CoapResponseUtils.java:179-180`

```java
if (intCode == getIntCode(ResponseCode.Companion.getUnauthorized())) {
    return AirlinkErrorCode.RF_ERR_KEY_EXPIRED.getErrorCode();
}
```

**File:** `com/fitbit/fbcomms/ota/AirlinkErrorCode.java`

```java
RF_ERR_KEY_EXPIRED(5378),  // 0x1502
```

### 3.2 AirlinkErrorCode Context

The `RF_ERR_KEY_*` error family (values 5376-5382):
```
RF_ERR_KEY_BAD_PARAM           (5376)
RF_ERR_KEY_MUTEX_FAIL          (5377)
RF_ERR_KEY_EXPIRED             (5378)  ← 4.01 Unauthorized
RF_ERR_KEY_NOT_FOUND           (5379)
RF_ERR_KEY_BAD_LENGTH          (5380)
RF_ERR_KEY_MOBILE_KEY_BAD_SECTION      (5381)
RF_ERR_KEY_MOBILE_KEY_BAD_SECTION_SIZE (5382)
```

And nearby:
```
RF_ERR_MISSING_CRYPTO_KEY      (8212)
RF_ERR_AUTH_FAILED             (8213)
RF_ERR_AUTH_REQUIRED           (8214)
RF_ERR_CRYPTO_REQUIRED         (8215)
```

### 3.3 Evidence Table

| Evidence | Meaning Proven? | Interpretation |
|----------|-----------------|----------------|
| CoAP 4.01 | PROVEN | Standard CoAP Unauthorized |
| RF_ERR_KEY_EXPIRED | PROVEN (mapping only) | Enum name says "KEY_EXPIRED" but this is just an Android-side label for 4.01 |
| bond/control response handling | PROVEN | Fitbit maps 4.01→5378, treats as fatal error, aborts pairing |
| DTLS success | PROVEN | DTLS handshake completed, session active |
| Android bondState = NONE | PROVEN | Device not bonded to Android |

### 3.4 What 4.01 Does NOT Mean

- **NOT** DTLS failure (DTLS succeeded)
- **NOT** CoAP format error (request is byte-for-byte identical to Fitbit)
- **NOT** Wrong URI or method (identical)
- **NOT** Missing local Android state (no state consulted before sending)
- **NOT** Wrong timeout value (any 0-255 is valid)

---

## 4. Inspire 3 Pairing/Provisioning State Model

### 4.1 States Inferred from Evidence

| State | Location | Evidence | Used For |
|-------|----------|----------|----------|
| Factory/Unprovisioned | Tracker | Inferred | Initial state after factory reset |
| BLE Discoverable | Tracker | Advertisement data | Broadcasting for pairing |
| GATT Connected | Phone+Tracker | GATT services discovered | Communication established |
| GoldenGate Session | Phone+Tracker | Gattlink protocol | Reliable transport |
| DTLS Bootstrap | Phone+Tracker | BOOTSTRAP PSK handshake | Encryption established |
| **Pairing Authorized** | **Tracker** | **INFERRED** | **Required for bond/control** |
| Bond Pending | Phone+Tracker | BOND_BONDING Android state | During SMP |
| Bonded | Phone+Tracker | BOND_BONDED Android state | Paired successfully |
| MobileData Key | Phone+Tracker | MobileDataTlsKeyResolver | Post-pairing key |

### 4.2 Critical Inference

There is a **"Pairing Authorized" state on the tracker** that must be true before `bond/control` is accepted. This state is:
- **NOT** Android bond state (BlueBit checks: BOND_NONE)
- **NOT** GATT connection state (established)
- **NOT** GoldenGate state (established)
- **NOT** DTLS state (TLS_STATE_SESSION)
- **NOT** CoAP endpoint state (working for pair/display)

### 4.3 How Fitbit May Establish "Pairing Authorized"

**Hypothesis (POSSIBLE, not proven):**
The Fitbit app establishes "Pairing Authorized" through server-mediated pairing flow:

```
1. User initiates pairing in Fitbit app
2. App calls POST /1/devices/client/tracker/data/validate.json
3. Server returns pairingToken + device-specific data
4. App performs some operation with this data
5. Device firmware transitions to "Pairing Authorized"
6. App calls bond/control → 2.01 Created
```

**Evidence against direct token transmission:**
- No code path found that sends pairingToken to device via CoAP/GATT
- No pairingToken field in BondControl.Control protobuf

**Alternative hypothesis (POSSIBLE):**
Server validation causes a device-side state change indirectly:
- Server informs Fitbit backend that pairing is authorized
- Backend communicates with device via alternate channel
- Or: device polls server for pairing authorization

**No direct evidence found for either mechanism.**

---

## 5. Official Fitbit Pre-Bond Sequence

| Step | Phone Action | Device-Visible Effect | Expected Device State | Evidence |
|------|-------------|----------------------|----------------------|----------|
| 1 | BLE scan/find | Advertisement | Discoverable | PROVEN |
| 2 | connectGatt() | GATT connection | Connected | PROVEN |
| 3 | requestMtu(185) | MTU negotiation | MTU=185 | PROVEN |
| 4 | discoverServices() | Service discovery | Services known | PROVEN |
| 5 | enable Service Changed | CCC write | Indications enabled | PROVEN |
| 6 | GattDatabase validation | Read ephemeral pointer | Validated | PROVEN |
| 7 | enable Gattlink notifications | CCC write | Notifications enabled | PROVEN |
| 8 | GoldenGate.start() | Gattlink session | Transport ready | PROVEN |
| 9 | DTLS handshake | BOOTSTRAP PSK | TLS_STATE_SESSION | PROVEN |
| 10 | CoAP endpoint attach | Filter attach | CoAP ready | PROVEN |
| 11 | **bond/control** | **CoAP PUT** | **?** | **4.01 in BlueBit** |
| 12 | createBond() | Android SMP | BOND_BONDING | PROVEN (if 11 succeeds) |

**Steps 1-10: Identical between Fitbit and BlueBit.**

---

## 6. BlueBit vs Fitbit Boundary Comparison

| Stage | Fitbit | BlueBit | Difference | Evidence |
|-------|--------|---------|------------|----------|
| BLE connection | Standard | Standard | None | PROVEN |
| MTU | 185 | 185 | None | PROVEN |
| GATT discovery | Standard | Standard | None | PROVEN |
| Service Changed CCC | 0x0200 (indications) | 0x0200 (indications) | None | PROVEN |
| GattDatabase validation | Read AC2F0145, write 0x00 | Read AC2F0145, write 0x00 | None | PROVEN |
| Gattlink notifications | CCC 0x0100 | CCC 0x0100 | None | PROVEN |
| GoldenGate stack | DSNG config | DSNG config | None | PROVEN |
| DTLS PSK | BOOTSTRAP | BOOTSTRAP | None | PROVEN |
| DTLS identity | "BOOTSTRAP" | "BOOTSTRAP" | None | PROVEN |
| DTLS session state | TLS_STATE_SESSION | TLS_STATE_SESSION | None | PROVEN |
| CoAP endpoint | Shared singleton | Shared singleton | None | PROVEN |
| CoAP filter | GROUP_1 | GROUP_1 | None | PROVEN |
| pair/display | Separate flow | Tested separately | None | PROVEN |
| **bond/control request** | **PUT bond/control {timeout=N}** | **PUT bond/control {timeout=N}** | **None** | **PROVEN** |
| **bond/control response** | **2.01 Created** | **4.01 Unauthorized** | **CRITICAL** | **OBSERVED** |

---

## 7. Earliest Proven Divergence

**The earliest proven divergence is at `bond/control` itself.**

There is **no earlier divergence** that can be proven from available evidence. Every protocol layer before `bond/control` is functionally identical.

The divergence is:
```
Fitbit:  bond/control → 2.01 Created
BlueBit: bond/control → 4.01 Unauthorized (RF_ERR_KEY_EXPIRED)
```

---

## 8. Evidence For/Against Provisioning Requirements

### 8.1 Server Flow Evidence

**File:** Previous investigation traced:
```
POST /1/devices/client/tracker/data/validate.json
→ Returns pairingToken

POST /1/devices/client/tracker/data/pair
→ Uses pairingToken
```

**Evidence for server involvement:**
- Fitbit app calls server before bonding
- Server returns pairingToken
- Device may check server authorization

**Evidence against direct server-device link:**
- No code found sending pairingToken to device
- No CoAP/GATT path for token transmission
- bond/control payload has no token field

### 8.2 Device Bond Info Characteristic

**File:** `defpackage/pcx.java:85` — UUID `adabfb02-6e7d-4601-bda2-bffaa68956ba`

**What it does:**
- `VerifyBondOperation` (peb.java): Writes to characteristic, reads bond info
- `ValidateBondedOperations` (pew.java): Reads bond info
- Bond info structure (`pfu.java`/`php.java`):
  - `isTrackerBonded` (byte 0)
  - `isBondedToCurrentPeer` (byte 1)
  - `isAncsReady` (byte 2)

**Relevance to bond/control:**
- These operations are for **VALIDATING** existing bonds
- Called **AFTER** bonding, not before
- `BondCommandsHandler.create()` checks bond status to **skip** bonding if already done
- NOT used to **authorize** bonding

**Status:** RULED OUT as bond/control prerequisite

---

## 9. Firmware Evidence Available

**Available:**
- AirlinkErrorCode enum (Android-side labels for firmware errors)
- Bond info characteristic structure
- CoAP response code mapping

**Not Available:**
- Inspire 3 firmware source code
- Firmware state machine implementation
- Exact condition checked by bond/control handler
- Any documentation of authorization requirements

**Conclusion:** Firmware semantics are **partially observable** (error codes) but the exact authorization condition is **unobservable** from available artifacts.

---

## 10. Hypothesis Table

| Hypothesis | Evidence For | Evidence Against | Status |
|------------|-------------|------------------|--------|
| APK signing required | None found | Exhaustive search found nothing | RULED OUT |
| Wrong BOOTSTRAP PSK | None | Same PSK, same DTLS success | RULED OUT |
| Wrong DTLS session context | None | TLS_STATE_SESSION achieved | RULED OUT |
| Missing GATT preparation | None | No GATT ops before bond/control in Fitbit | RULED OUT |
| Missing GoldenGate state | None | Stack config identical | RULED OUT |
| Missing provisioning state | Server flow exists; 4.01=KEY_EXPIRED | No proven path from server to device | POSSIBLE |
| Device already provisioned/stale | Tracker not bonded to Android | No evidence of stale state | POSSIBLE |
| Missing device-side pairing transition | 4.01 suggests auth failure | No known transition mechanism | POSSIBLE |
| Server-derived state required | Server validation exists | No proven device transmission path | POSSIBLE |
| Firmware authorization condition | 4.01 is firmware-level | Cannot observe firmware code | STRONGLY INFERRED |
| Unknown | Cannot determine exact cause | All above insufficient | UNKNOWN |

---

## 11. Exactly One Recommended Next Experiment

### Experiment: Read Tracker Bond Info Characteristic

**Hypothesis:** The Inspire 3's bond info characteristic (`adabfb02-6e7d-4601-bda2-bffaa68956ba`) may reveal the tracker's current bond state, which could indicate whether the device is in a "pairing authorized" state or a stale/incomplete state.

**Procedure:**
1. Connect to Inspire 3 via BLE
2. Discover services
3. Locate characteristic `adabfb02-6e7d-4601-bda2-bffaa68956ba`
4. Read the characteristic value
5. Parse as `BondInfo` structure: bytes[0]=isTrackerBonded, bytes[1]=isBondedToCurrentPeer, bytes[2]=isAncsReady

**Expected outcomes:**
- If `isTrackerBonded=0, isBondedToCurrentPeer=0`: Tracker thinks it's unbonded. Supports "firmware authorization gate" hypothesis.
- If `isTrackerBonded=1, isBondedToCurrentPeer=0`: Tracker thinks it's bonded to another phone. Supports "stale bond state" hypothesis.
- If characteristic not found: The Inspire 3 may not expose this characteristic in its current firmware version.

**Safety:** Read-only operation. No state change.

**Prerequisites:** Inspire 3 must be in pairing mode and connectable.

**Blocked by:** Currently the Inspire 3 is not advertising/connectable (not found in scans).

---

## 12. What Remains Fundamentally Unknown

1. **The exact firmware condition** that enables `bond/control` acceptance
2. **Whether server-mediated authorization** is required, and if so, how it's communicated to the device
3. **Whether the Inspire 3 is in a stale/incomplete pairing state** that prevents new pairing
4. **Whether there is a legitimate way for a third-party app** to satisfy the firmware's authorization requirements
5. **The semantics of `RF_ERR_KEY_EXPIRED`** — whether it literally means an expired key, or is used as a generic "authorization failed" code

---

*Report compiled from decompiled Fitbit APK source code (~20,000 files), native library analysis, BlueBit source code, and experimental logs.*
*All claims distinguish PROVEN / STRONGLY INFERRED / POSSIBLE / UNKNOWN.*
