# Reference CoAP Read-Only Audit — Fitbit Air Notification Bridge

**Project audited:** `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate`  
**Scope:** Kotlin sources that build or send CoAP requests over an established Golden Gate / DTLS link.  
**Method:** Static, read-only file inspection. No requests were sent, no secrets extracted, no files modified.

---

## 1. CoAP method and path inventory

| # | Source file | Line(s) | Method | Path | Classification | Notes |
|---|-------------|---------|--------|------|----------------|-------|
| 1 | `app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt` | 130 | PUT | `/settings` | **State-changing / write-like** | Used to *read* haptics intensity, but uses PUT with a request body. Documented as a semantic read, not a true CoAP GET. |
| 2 | `app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt` | 367 | POST | `/settings` | **State-changing** | Writes haptics intensity value. |
| 3 | `app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt` | 376 | PUT | `/settings` | **State-changing / write-like** | Same semantic read as #1; PUT with body. |
| 4 | `app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt` | 493 | GET | `/{path}` (variable) | **Read-only** | Generic probe helper; used for `READ_ONLY_PROBE_PATHS` (see below). |
| 5 | `app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt` | 589 | GET | `/alarms` | **Read-only** | Lists existing alarms; parsed with `AirAlarmProtocol.parseDeviceAlarms`. |
| 6 | `app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt` | 600 | POST | `/alarms` | **State-changing** | Creates an alarm. |
| 7 | `app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt` | 612 | DELETE | `/alarms/{alarmId}` | **State-changing** | Deletes an alarm. |
| 8 | `app/src/main/kotlin/com/fitbit/goldengatehost/AirHapticPatternExecutor.kt` | 88 | PUT | `/settings` | **State-changing / write-like** | Same semantic read as CoapActivity; PUT with body. |
| 9 | `app/src/main/kotlin/com/fitbit/goldengatehost/AirHapticPatternExecutor.kt` | 103 | POST | `/settings` | **State-changing** | Writes haptics intensity. |
| 10 | `app/src/main/kotlin/com/fitbit/goldengatehost/coap/BsdCoapActivity.kt` | 128 | GET | `helloworld` | **Read-only** | Demo request against local server handler; returns "Hello world!". |
| 11 | `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/coap/Endpoint.kt` | 83 | GET | `{path}` (generic) | **Read-only** | Library convenience helper `resource(path, parser)`. |
| 12 | `GoldenGateBindings/src/test/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpointTest.kt` | 75, 108, 129, 154, 175, 190, 218, 258, 372, 398, 484 | POST | `echo` | **Test-only / state-changing** | Echo handler tests; not production Air traffic. |
| 13 | `GoldenGateBindings/src/test/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpointTest.kt` | 311, 327, 351 | GET | `non_existing_path`, `echo` | **Test-only / read-only** | Unit tests only. |

---

## 2. Resource paths discovered in source

### 2.1 Hard-coded Air device paths (production/demo code)

- `/settings` — haptics intensity read/write. Uses a custom protobuf (`DirectSettings`) payload.
- `/alarms` — alarm list (GET), alarm creation (POST).
- `/alarms/{alarmId}` — alarm deletion (DELETE).
- `helloworld` — demo server resource in `BsdCoapActivity`.
- `echo` — test/server echo resource.

### 2.2 Bootstrap read-only probe path list

In `CoapActivity.kt` companion object, lines 681–689:

```kotlin
private val READ_ONLY_PROBE_PATHS = listOf(
    "/.well-known/core",
    "/alarms",
    "/device/info",
    "/pair/status",
    "/pair/display",
    "/bond/control",
    "/sync/status",
    "/sync/config"
)
```

These are sent via the generic `probeGet()` helper (line 492) as **GET** requests. No response parsers are provided for `/device/info`, `/pair/status`, `/pair/display`, `/bond/control`, `/sync/status`, or `/sync/config` in the audited Kotlin sources.

---

## 3. Payloads and response formats

### 3.1 `/settings` (PUT / POST)

Defined in `app/src/main/kotlin/com/fitbit/goldengatehost/AirDirectSettingsProtocol.kt`:

| Direction | Method | Payload bytes | Meaning |
|-----------|--------|---------------|---------|
| Request | PUT | `0x0A 0x01 0x01` | `DirectSettings.SettingsRequest { setting_type: SETTING_TYPE_HAPTICS }` |
| Request | POST | `0x0A 0x04 0x0A 0x02 0x08 {value}` | `DirectSettings.SettingsResponse { settings { haptics_setting { haptics_intensity: value } } }` |
| Response | (to PUT read) | Protobuf `SettingsResponse` | Parsed by `AirDirectSettingsProtocol.parseHapticsIntensity()` to extract intensity. |

Valid intensity values: `1` = off, `2` = low, `3` = high.

**Important:** The read path is implemented as **PUT with a request body**, not as a CoAP GET. The source comments describe it as a "semantic read" that contains no setting value and therefore does not change intensity, but it is still a PUT request.

### 3.2 `/alarms` (GET)

Defined in `app/src/main/kotlin/com/fitbit/goldengatehost/AirAlarmProtocol.kt`:

- Response body is a protobuf `DeviceAlarms` message.
- Parsed by `AirAlarmProtocol.parseDeviceAlarms()` into `List<AlarmRecord>`.
- `AlarmRecord` fields: `id`, `hour`, `minute`, `second`, `enabled`, `repeats`.

### 3.3 `/.well-known/core` (GET)

Handled in `probeGet()` (line 492). If the body is non-empty, it is interpreted as UTF-8 link-format text, whitespace-normalized, and truncated to 240 characters for display. No formal parser is used.

### 3.4 `helloworld` (GET)

Server handler in `app/src/main/kotlin/com/fitbit/goldengatehost/coap/handler/HelloWorldResourceHandler.kt`:

- Returns `Content-Format: text/plain` body `"Hello world!"`.

---

## 4. Observe support

The audited Kotlin bindings do **not** expose CoAP Observe (RFC 7641):

- `OptionNumber.kt` (lines 13–58) enumerates supported options: `IF_MATCH`, `ETAG`, `IF_NONE_MATCH`, `LOCATION_PATH`, `URI_PATH`, `CONTENT_FORMAT`, `MAX_AGE`, `URI_QUERY`, `ACCEPT`, `LOCATION_QUERY`, `BLOCK2`, `BLOCK1`, `START_OFFSET`.
- `Observe` (option number 6) is **absent**.
- `OutgoingRequestBuilder` exposes no observe flag.

Therefore, **no observe subscriptions** are available in this reference code.

---

## 5. State-changing vs. read-only summary

### State-changing or write-like operations

- `POST /settings` — writes haptics intensity.
- `PUT /settings` — semantically reads haptics intensity but uses PUT with a body.
- `POST /alarms` — creates an alarm.
- `DELETE /alarms/{alarmId}` — deletes an alarm.
- `AirBridgeOneShotService` — the production one-shot service does not issue CoAP requests directly; it delegates to `AirHapticPatternExecutor.execute()`, which performs `PUT /settings` (read probe) and `POST /settings` (write/restore) operations.

### Read-only operations

- `GET /.well-known/core` — resource discovery; true read-only.
- `GET /alarms` — lists alarms; true read-only and has a documented protobuf response parser.
- `GET /device/info` — appears in probe list as GET; no response parser in audited source.
- `GET /pair/status` — appears in probe list as GET; no response parser in audited source.
- `GET /sync/status` — appears in probe list as GET; no response parser in audited source.
- `GET /sync/config` — appears in probe list as GET; no response parser in audited source.
- `GET helloworld` — demo resource; true read-only.
- Generic `Endpoint.resource(path, parser)` helper — issues GET.

### Caution: pair/bond paths

- `GET /pair/display` and `GET /bond/control` are present in the read-only probe list, but their names imply pairing/bonding control surfaces. The audited code only performs GET on them and does not document their response format. Whether a GET to these resources is side-effect-free on a live device **cannot be confirmed from this source alone**; the names suggest they may trigger or be part of pairing/bonding flows.

---

## 6. Evidence-based recommendation

**Yes, documented read-only CoAP queries exist** in the reference project and could be sent over an established DTLS session:

1. **`GET /alarms`** — fully documented in source with a protobuf response parser (`AirAlarmProtocol`). It reads alarm state and does not create, modify, or delete anything.
2. **`GET /.well-known/core`** — standard CoAP resource discovery; read-only, though response parsing is only informal (UTF-8 link-format text).

Other GET paths (`/device/info`, `/pair/status`, `/sync/status`, `/sync/config`) are listed in the bootstrap probe but lack documented response parsers in the audited Kotlin code. The `/pair/display` and `/bond/control` paths are additionally suspect because their names associate them with pairing/bonding control operations.

The most commonly demonstrated Air-specific read path, `/settings` for haptics intensity, is **not** a true read-only CoAP GET; it is implemented as `PUT /settings` with a request body. It is described by the authors as a semantic read that does not carry a setting value, but it still uses a write method.

**Bottom line:** For a safe, read-only query over an existing DTLS session, the best-supported candidate in this reference code is **`GET /alarms`** (with `AirAlarmProtocol.parseDeviceAlarms` as the expected response format). `GET /.well-known/core` is also read-only but less formally documented in this codebase.

---

## 7. Source locations referenced

- `app/src/main/kotlin/com/fitbit/goldengatehost/AirBridgeOneShotService.kt` — no direct CoAP requests; delegates to `AirHapticPatternExecutor.execute()`.
- `app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt` — lines 109–163, 365–390, 492–510, 588–619, 681–689.
- `app/src/main/kotlin/com/fitbit/goldengatehost/AirHapticPatternExecutor.kt` — lines 87–99, 102–109.
- `app/src/main/kotlin/com/fitbit/goldengatehost/AirDirectSettingsProtocol.kt` — protobuf codec for `/settings`.
- `app/src/main/kotlin/com/fitbit/goldengatehost/AirAlarmProtocol.kt` — protobuf codec for `/alarms`.
- `app/src/main/kotlin/com/fitbit/goldengatehost/coap/BsdCoapActivity.kt` — lines 127–138 (`GET helloworld`).
- `app/src/main/kotlin/com/fitbit/goldengatehost/coap/handler/AddHandlerHelper.kt` — registers `helloworld` and `echo` handlers.
- `app/src/main/kotlin/com/fitbit/goldengatehost/coap/handler/HelloWorldResourceHandler.kt` — `GET helloworld` response.
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/coap/Endpoint.kt` — lines 73–85 (`resource()` GET helper).
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/coap/data/Method.kt` — GET/POST/PUT/DELETE enum.
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/coap/data/OptionNumber.kt` — confirms no Observe option.
