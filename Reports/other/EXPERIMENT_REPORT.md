# BlueBit — Local Fitbit Pairing Backend Experiment Report

**Date:** 2026-09-02
**Status:** Implementation complete. Device-side experiment blocked on Inspire 3 availability.

---

## 1. Objective

Determine whether Fitbit cloud responses influence the Inspire 3 pairing authorization state that affects `bond/control`.

- Fitbit app: `bond/control` → `2.01 Created`
- BlueBit: `bond/control` → `4.01 Unauthorized`

**Goal:** Not to bypass Fitbit security. Goal is to determine if pairing-related server responses alter the client-side protocol flow before `bond/control`.

---

## 2. Methodology

### 2.1 Research Phase

Traced the Fitbit APK to identify:
1. All pairing-related HTTP endpoints
2. Request/response models for each endpoint
3. Data flow from server responses to device layer

### 2.2 Implementation Phase

1. **Mock Server:** Python HTTP server implementing all discovered endpoints
2. **HTTP Client:** Ktor-based Kotlin client (`FitbitPairingApi`) in BlueBit commonMain
3. **Experiment Integration:** `ServerPairingExperiment` class that calls validate+pair before BLE bonding

### 2.3 Experiment Design

```
Step 1: POST validate.json -> get pairingToken
Step 2: POST pair.json with token -> get server response
Step 3: (Discard unparseable protobuf response)
Step 4: Proceed with normal BLE/GoldenGate/bond/control
Step 5: Observe if bond/control response changes
```

**Hypothesis:** If `bond/control` still returns `4.01`, server responses do NOT affect firmware authorization.

---

## 3. Endpoint Inventory

| Endpoint | Method | Query Params | Body | Response |
|----------|--------|-------------|------|----------|
| `/1/devices/client/tracker/data/validate.json` | POST | `btleName`, `secret`, `btAddress` | base64 device data | `{"pairingToken", "peripheralDeviceType"}` |
| `/1/devices/client/tracker/data/pair.json` | POST | `pairingToken`, `challengesRun`, `challengeResults`, `maxCommsVersion` | base64 device data | base64 binary blob |
| `/1/devices/client/tracker/data/ack.json` | POST | `ackToken`, `challengesRun`, `challengeResults` | - | `{"status": "ok"}` |
| `/1/devices/client/tracker/data/sync.json` | POST | `trigger`, `btleName`, `maxCommsVersion` | base64 device data | base64 binary blob |
| `/1/devices/client/tracker/data/sync/app.json` | POST | `trigger`, `btleName`, `maxCommsVersion`, `mediaEvent`, `req` | base64 device data | base64 binary blob |
| `/1/devices/client/tracker/data/whitelabel-pair.json` | POST | `maxCommsVersion` | device data | base64 binary blob |

**Source:** `defpackage/sxm.java`, `defpackage/sxu.java`, `defpackage/sxs.java`, `defpackage/qzy.java`

---

## 4. Data Flow Analysis

### 4.1 Validate Flow

```
Device (BLE advert / identity data)
  -> BlueBit
    -> base64 encode
      -> POST /validate.json?btleName=X&secret=Y&btAddress=Z
        -> Server
          -> JSON: {pairingToken: "abc", peripheralDeviceType: "Inspire 3"}
            -> BlueBit stores pairingToken
```

**Key field:** `pairingToken` — required for all subsequent server calls.

### 4.2 Pair Flow

```
BlueBit (has pairingToken)
  -> collects device data (UNKNOWN format)
    -> base64 encode
      -> POST /pair.json?pairingToken=...
        -> Server
          -> base64 binary response (UNKNOWN protobuf format)
            -> BlueBit decodes
              -> saves to temp file
                -> (official app parses protobuf here)
                  -> sends some fields to device via CoAP
```

**Critical gap:** The binary body sent to `pair` and the binary response are both in unknown protobuf formats. BlueBit cannot meaningfully construct or parse them.

### 4.3 Device-Side Pairing (No Server)

The official app also sends these CoAP commands to the device during pairing:

| CoAP Endpoint | Method | Purpose |
|--------------|--------|---------|
| `pair/affiliation/request` | GET | Device affiliation check |
| `pair/affiliation/response` | POST | Response to affiliation |
| `pair/attestation/request` | GET | Device attestation |
| `pair/attestation/response` | POST | Response to attestation |
| `pair/registration/request` | GET | Device registration |
| `pair/registration/response` | POST | Response to registration |
| `pair/cancel` | POST | Cancel pairing |

**Source:** `defpackage/ahsd.java`

These are **device-to-device** CoAP commands that do NOT involve the cloud server.

---

## 5. Critical Finding: Two Pairing Paths

### Path A: `ahsr.h()` — Direct Bond (NO server calls)

```
ahsr.h(context)
  -> ahso coroutine
    -> pgp.createNewBond(context)
      -> BondCommandsHandler.create()
        -> bond/control CoAP PUT
```

This path goes directly from GoldenGate setup to `bond/control` with **zero server interaction**.

### Path B: `ahsr.i()` — Full Pairing (WITH server calls)

```
ahsr.i(context)
  -> ahsp coroutine
    -> server validate + pair
    -> device CoAP pair/affiliation, pair/attestation, pair/registration
    -> pgp.createNewBond(context)
      -> bond/control CoAP PUT
```

This path calls the server AND performs device-side pairing CoAP commands before `bond/control`.

### Path Selection Condition: UNKNOWN

The decompiled code does not reveal what condition selects between `ahsr.h()` and `ahsr.i()`. It may depend on:
- Whether the device is already known to the account
- Device firmware version
- User account state
- Device provisioning type

---

## 6. Implementation Status

### 6.1 Mock Server

**File:** `BlueBitApp/mock-server/fitbit_mock_server.py`

**Status:** ✅ Complete and tested

**Test results:**
```
POST /validate.json -> {"pairingToken": "mock-...", "peripheralDeviceType": "Inspire 3"}
POST /pair.json -> base64 binary blob (16 bytes)
POST /ack.json -> {"status": "ok"}
POST /sync.json -> base64 binary blob (32 bytes)
```

**Features:**
- All 6 endpoints implemented
- Request logging with headers, query params, body preview
- Configurable responses via `CONFIG` dict
- CORS enabled for cross-origin requests
- Zero dependencies (Python stdlib only)

### 6.2 BlueBit HTTP Client

**File:** `BlueBitApp/app/src/commonMain/kotlin/bluebit/api/FitbitPairingApi.kt`

**Status:** ✅ Compiles successfully

**Features:**
- `validate()`, `pair()`, `ack()`, `sync()` methods
- Ktor client with JSON serialization
- Configurable base URL (default: `http://10.0.2.2:8080` for emulator)

### 6.3 Experiment Integration

**File:** `BlueBitApp/app/src/androidMain/kotlin/bluebit/bluetooth/android/ServerPairingExperiment.kt`

**Status:** ✅ Integrated into `MainActivity`

**Features:**
- `runExperiment(device)` — calls validate+pair, logs results
- `testServerConnection()` — quick connectivity check
- `testServerThenBond(address)` — callable from UI
- `testMockServerConnection()` — server reachability test

### 6.4 Build Verification

```
:app:compileDebugKotlinAndroid -> BUILD SUCCESSFUL
:app:desktopJar -> BUILD SUCCESSFUL
```

---

## 7. Experiment Execution Status

### 7.1 What Was Tested

| Test | Status | Result |
|------|--------|--------|
| Mock server startup | ✅ | Running on localhost:8765 |
| Status endpoint | ✅ | Returns correct JSON |
| Validate endpoint | ✅ | Returns pairingToken + deviceType |
| Pair endpoint | ✅ | Returns base64 binary blob |
| Ack endpoint | ✅ | Returns `{"status": "ok"}` |
| Sync endpoint | ✅ | Returns base64 binary blob |
| Kotlin client compilation | ✅ | BUILD SUCCESSFUL |
| Desktop jar build | ✅ | BUILD SUCCESSFUL |

### 7.2 What Was NOT Tested (Blocked)

| Test | Blocker |
|------|---------|
| Kotlin client against mock server (runtime) | Desktop run blocked by Compose mainClass conflict; can run manually with `java -jar` |
| Android app calling mock server | Requires APK build + install + emulator/physical device |
| Server calls before bond/control | **Inspire 3 not advertising/connectable** |
| bond/control response change | **Inspire 3 not advertising/connectable** |

---

## 8. Preliminary Analysis

### 8.1 Evidence For "Server Does NOT Affect bond/control"

1. **Path A exists:** The official app has a code path (`ahsr.h()`) that sends `bond/control` with **zero** prior server interaction.
2. **No server data in bond/control payload:** The CoAP `bond/control` request contains only `operationTimeout`. There is no pairingToken, no server-derived data, no credential.
3. **No server call between GoldenGate and bond/control:** In Path A, the sequence is `GoldenGate.connect() -> bond/control` with nothing in between.

### 8.2 Evidence For "Server MAY Affect bond/control"

1. **Path B exists:** The official app has a code path (`ahsr.i()`) that calls server validate+pair AND device-side CoAP pairing commands before `bond/control`.
2. **Unknown path selection:** If Path B is used for new/unprovisioned devices, then server data might be required.
3. **4.01 = KEY_EXPIRED:** The firmware error suggests a key/credential issue, which could be server-provisioned.

### 8.3 Current Assessment

**Most likely:** The firmware authorization gate is independent of server responses. The 4.01 is likely caused by:
- A device-side state that BlueBit doesn't establish (via device CoAP commands)
- A firmware-level pairing gate that requires Fitbit infrastructure
- A stale/incomplete bond state on the Inspire 3

**Cannot rule out:** Server-mediated authorization if Path B is the required path for the Inspire 3.

---

## 9. How to Complete the Experiment

### 9.1 Prerequisites

1. Inspire 3 in pairing mode and advertising
2. Android emulator or physical device with BlueBit APK installed
3. Mock server running on host (accessible from Android)

### 9.2 Steps

1. **Start mock server:**
   ```bash
   python BlueBitApp/mock-server/fitbit_mock_server.py --port 8080
   ```

2. **Build and install BlueBit:**
   ```bash
   cd BlueBitApp
   ./gradlew :app:installDebug
   ```

3. **From Android, find the mock server:**
   - Emulator: `http://10.0.2.2:8080`
   - Physical device: host LAN IP (e.g., `http://192.168.1.100:8080`)
   - Edit `ServerPairingExperiment.kt` if needed

4. **Run the experiment:**
   - In BlueBit UI, tap "Test Server Connection" to verify reachability
   - Scan for Inspire 3, tap "Server Then Bond" on the device row
   - Observe logcat for server call results
   - Then run GoldenGate/bond/control
   - Observe if `bond/control` response changes

5. **Expected outcomes:**
   - If `bond/control` still returns `4.01`: Server responses do NOT affect authorization
   - If `bond/control` returns `2.01`: Server responses DO affect authorization (but mock data may be insufficient)

### 9.3 Alternative: Manual Testing

Instead of the full experiment, test individual hypotheses:

**Test A:** Device-side CoAP pairing commands
```
1. Connect BLE + GoldenGate
2. CoAP GET pair/affiliation/request
3. Send response via POST pair/affiliation/response
4. Repeat for attestation, registration
5. Then bond/control
```

**Test B:** Different bond/control timing
```
1. Connect BLE + GoldenGate
2. Wait varying durations (0s, 5s, 30s)
3. bond/control
4. Observe if timing affects response
```

**Test C:** Different DTLS/session states
```
1. Connect BLE + GoldenGate
2. Verify DTLS session state
3. Try bond/control immediately vs after delay
```

---

## 10. Files Created

| File | Purpose |
|------|---------|
| `BlueBitApp/mock-server/fitbit_mock_server.py` | Python mock server |
| `BlueBitApp/mock-server/README.md` | Server usage guide |
| `BlueBitApp/mock-server/ENDPOINT_INVENTORY.md` | Full API spec + data flow |
| `BlueBitApp/mock-server/EXPERIMENT_REPORT.md` | This report |
| `BlueBitApp/app/src/commonMain/kotlin/bluebit/api/FitbitPairingApi.kt` | Ktor HTTP client |
| `BlueBitApp/app/src/androidMain/kotlin/bluebit/bluetooth/android/ServerPairingExperiment.kt` | Experiment integration |
| `BlueBitApp/app/src/desktopMain/kotlin/bluebit/PairingApiTest.kt` | Desktop smoke test |

---

## 11. Conclusion

**Implementation:** ✅ Complete

**Device-side experiment:** ⏸ Blocked (Inspire 3 not available)

**Preliminary finding:** The official Fitbit app has a code path that sends `bond/control` without any server interaction. This strongly suggests that server responses are not required for `bond/control` authorization. However, the Inspire 3 may require a different pairing path (Path B) that does involve server calls.

**Next step:** When the Inspire 3 is available, run the `Server Then Bond` experiment in BlueBit. If `bond/control` still returns `4.01` after server calls, then server responses definitively do NOT affect the firmware authorization state.

---

*Report compiled from decompiled Fitbit APK analysis, mock server testing, and BlueBit source code integration.*
