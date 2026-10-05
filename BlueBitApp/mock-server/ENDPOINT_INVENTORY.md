# Fitbit Pairing Endpoint Inventory & Data Flow Analysis

## Summary

**Finding: BlueBit currently makes NO HTTP server calls.**

The Fitbit official app calls several cloud endpoints during device pairing. BlueBit does not implement any of these calls. This means the mock server experiment requires adding HTTP client infrastructure to BlueBit, not merely redirecting existing calls.

---

## Endpoint Inventory

### 1. `POST /1/devices/client/tracker/data/validate.json`

**Purpose:** Authenticate/authorize the device with Fitbit's cloud. Returns a pairing token needed for subsequent operations.

**Implementation:** `defpackage/sxm.java:364-382` (method `f`)

**Request Query Parameters:**
| Parameter | Source | Description |
|-----------|--------|-------------|
| `btleName` | Device advertisement | BLE advertised name (e.g., "Inspire 3") |
| `secret` | User input / derived | Pairing PIN or secret |
| `btAddress` | Device | MAC address (with colons removed) |

**Request Headers:**
| Header | Value |
|--------|-------|
| `Device-Data-Encoding` | `BASE_64` |

**Request Body:**
- Base64-encoded binary data from the tracker (likely a device identity/dump)
- Content-Type: `text/plain`

**Response Body (JSON):**
| Field | Type | Description |
|-------|------|-------------|
| `pairingToken` | string | Opaque token required for `/pair` call |
| `peripheralDeviceType` | string | Device model name (e.g., "Inspire 3") |

**Response Headers:**
| Header | Description |
|--------|-------------|
| `Fitbit-Tracker-Challenge` | Bitmask of required security challenges (optional) |

**Data Flow:**
```
Device (BLE advert data / identity)
  -> BlueBit
    -> base64 encode
      -> POST validate.json
        -> Server
          -> JSON response {pairingToken, peripheralDeviceType}
            -> BlueBit stores pairingToken
```

---

### 2. `POST /1/devices/client/tracker/data/pair.json`

**Purpose:** Exchange pairing token + device data for server-derived device configuration.

**Implementation:** `defpackage/sxm.java:100-170` (method `d`)

**Request Query Parameters:**
| Parameter | Source | Description |
|-----------|--------|-------------|
| `pairingToken` | `validate` response | Token from validate step |
| `challengesRun` | Client | Bitmask of challenges attempted (sxn enum) |
| `challengeResults` | Client | Bitmask of challenge results |
| `maxCommsVersion` | Client | Protocol version (from `quw.a()`) |

**Request Headers:**
| Header | Value |
|--------|-------|
| `Device-Data-Encoding` | `BASE_64` |
| `X-Fitbit-CompanionAPIVersion` | App version |

**Request Body:**
- Base64-encoded binary data from the tracker (likely pairing/registration dump)
- Content-Type: `text/plain`

**Response Body:**
- Base64-encoded binary string
- Contains server-derived device configuration / pairing data
- Saved to temp file by client
- Returned as `sxp` object (URI + byte array + headers)

**Data Flow:**
```
BlueBit (has pairingToken from validate)
  -> collects device data
    -> base64 encode
      -> POST pair.json?pairingToken=...
        -> Server
          -> base64 binary response
            -> BlueBit decodes to byte[]
              -> saves to temp file
                -> processes protobuf data
                  -> sends relevant parts to device via CoAP
```

---

### 3. `POST /1/devices/client/tracker/data/ack.json`

**Purpose:** Acknowledge receipt/processing of sync data.

**Implementation:** `defpackage/qzy.java:99-110`

**Request Query Parameters:**
| Parameter | Description |
|-----------|-------------|
| `ackToken` | Token from previous sync response |
| `challengesRun` | Challenge bitmask |
| `challengeResults` | Challenge result bitmask |

**Response:** Minimal status response.

---

### 4. `POST /1/devices/client/tracker/data/sync.json`

**Purpose:** Upload device data (mega dump) and receive sync data.

**Implementation:** `defpackage/sxm.java:200-300` (method `e`)

**Request Query Parameters:**
| Parameter | Description |
|-----------|-------------|
| `trigger` | Sync trigger reason (e.g., "USER", "CLIENT") |
| `btleName` | Device BLE name |
| `maxCommsVersion` | Protocol version |
| `includeApps` | (legacy app sync) |
| `limitAppSize` | (legacy app sync) |
| `mediaEvent` | (if sync request has event) |
| `req` | Request UUID |

**Request Headers:**
| Header | Value |
|--------|-------|
| `Device-Data-Encoding` | `BASE_64` |
| `X-Client-Criticality` | `CRITICAL` or `CRITICAL_PLUS` |
| `X-Fitbit-CompanionAPIVersion` | App version |

**Request Body:**
- Base64-encoded binary data from tracker (mega dump)

**Response Headers:**
| Header | Description |
|--------|-------------|
| `Fitbit-Tracker-Challenge` | Challenge bitmask (for USER/USER_RETRY triggers) |

**Response Body:**
- Base64-encoded binary sync data
- Saved to temp file

---

### 5. `POST /1/devices/client/tracker/data/sync/app.json`

**Purpose:** App-specific sync endpoint.

**Implementation:** `defpackage/sxs.java` (enum APP_SYNC)

Same structure as regular sync but with `sxs.APP_SYNC` URL.

---

### 6. `POST /1/devices/client/tracker/data/whitelabel-pair.json`

**Purpose:** Pairing for white-label (branded) devices.

**Implementation:** `defpackage/sxu.java:19-28`

Same structure as `pair.json` but with fewer query parameters (only `maxCommsVersion`).

---

## Data Flow: Server Response -> Device

### Proven Flows

**Flow 1: validate -> pair (PROVEN)**
```
validate.json response
  -> pairingToken (string)
    -> stored by client
      -> passed as query param to pair.json
```

**Flow 2: pair -> device config (INFERRED)**
```
pair.json response
  -> base64 binary
    -> decoded to byte[]
      -> saved to temp file
        -> parsed by protobuf (unknown message type)
          -> SOME fields sent to device via CoAP
```

**Flow 3: sync -> device data (PROVEN)**
```
sync.json response
  -> base64 binary
    -> decoded to byte[]
      -> saved to temp file
        -> sent to device via GoldenGate/CoAP
```

### Unproven / Unknown Flows

**Critical Unknown: Does pair response data affect bond/control?**

The `pair.json` response is processed by the client, but the exact protobuf structure and which fields (if any) are sent to the device before `bond/control` is **unknown**.

The official app's `ahsr.h()` path calls `createNewBond()` directly after GoldenGate setup, which does `bond/control` with NO prior server calls. This suggests server data is NOT required for `bond/control`.

However, the `ahsr.i()` path does a full pairing including server calls and device CoAP `pair/affiliation`, `pair/attestation`, `pair/registration` BEFORE `bond/control`. If this path is used for new devices, server data might be required.

**Device-Side CoAP Pairing Endpoints (no server involved):**
- `GET pair/affiliation/request` -> device response -> `POST pair/affiliation/response`
- `GET pair/attestation/request` -> device response -> `POST pair/attestation/response`
- `GET pair/registration/request` -> device response -> `POST pair/registration/response`
- `POST pair/cancel`

These are called by `ahsd` (via `pgp.sendGenericCommand`) and do NOT involve the cloud server.

---

## Request/Response Model Details

### Validate Request

```http
POST /1/devices/client/tracker/data/validate.json?btleName=Inspire3&secret=123456&btAddress=A4CF12345678 HTTP/1.1
Host: api.fitbit.com
Device-Data-Encoding: BASE_64
Content-Type: text/plain

<base64-encoded device data>
```

### Validate Response

```http
HTTP/1.1 200 OK
Content-Type: application/json
Fitbit-Tracker-Challenge: 0

{"pairingToken":"abc123...","peripheralDeviceType":"Inspire 3"}
```

### Pair Request

```http
POST /1/devices/client/tracker/data/pair.json?pairingToken=abc123...&challengesRun=0&challengeResults=0&maxCommsVersion=2 HTTP/1.1
Host: api.fitbit.com
Device-Data-Encoding: BASE_64
X-Fitbit-CompanionAPIVersion: 4.28
Content-Type: text/plain

<base64-encoded device data>
```

### Pair Response

```http
HTTP/1.1 200 OK
Content-Type: text/plain

<base64-encoded binary config data>
```

---

## Challenge System (sxn enum)

The `Fitbit-Tracker-Challenge` header uses a bitmask of `sxn` enum values:

```java
enum sxn {
    // Values inferred from sxm.b() bitmask logic
    // Bit N set = challenge N required
}
```

The client computes `challengesRun` and `challengeResults` bitmasks and sends them in subsequent requests.

For the mock server, `challenge_mask: 0` means no challenges required.

---

## Key Finding: No Server -> bond/control Path

**Evidence:**
- `ahsr.h(Context)` -> `ahso` coroutine -> `pgp.createNewBond(context)` -> `BondCommandsHandler.create()` -> `bond/control` CoAP PUT
- This path does NOT call `validate`, `pair`, or any server endpoint
- It does check Android `bondState` and optionally calls `peripheralBondStatusChecker`

**Conclusion:** The official Fitbit app has a code path that sends `bond/control` without any prior server interaction. If this path is the one used for the Inspire 3, then server responses do NOT affect `bond/control`.

**Caveat:** It is possible that `ahsr.i()` (full pairing with server calls) is used instead of `ahsr.h()` for new/unpaired devices. The condition that selects between these paths is **unknown** from decompiled code.

---

## Experiment Design

### Hypothesis

If BlueBit implements the server pairing flow (`validate` -> `pair`) before `bond/control`, and `bond/control` still returns `4.01 Unauthorized`, then server responses do NOT influence the pairing authorization state.

### Required BlueBit Changes

1. Add HTTP client (Ktor or OkHttp)
2. Collect device data for `validate` body (unknown format)
3. Call `validate`, extract `pairingToken`
4. Collect device data for `pair` body (unknown format)
5. Call `pair` with token
6. Process `pair` response (unknown protobuf format)
7. Send processed data to device (if required)
8. Then call `bond/control`

### Blockers

1. **Device data format unknown:** The binary body sent to `validate` and `pair` comes from the device. BlueBit would need to know what device data to collect.
2. **Pair response format unknown:** The binary response from `pair` is protobuf-encoded. Without the `.proto` definition, BlueBit cannot meaningfully process it.
3. **Device not available:** The Inspire 3 is not currently advertising/connectable.

### Alternative Experiment

Instead of implementing the full flow, BlueBit could:
1. Call mock `validate` with placeholder body
2. Call mock `pair` with placeholder body + token
3. Discard the response (since we can't process it)
4. Call `bond/control`
5. Observe if 4.01 persists

This tests the weakest form of the hypothesis: "Does the mere existence of server calls (regardless of content) affect bond/control?"

If `bond/control` still fails, then the answer is "no" — the firmware authorization gate is independent of server responses.
