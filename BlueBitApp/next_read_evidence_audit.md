# BlueBit — Next CoAP Read-Only Evidence Audit

**Date:** 2026-10-10  
**Scope:** Determine whether existing local evidence establishes a safe, useful next read-only CoAP operation for the Fitbit Inspire 3.  
**Constraint:** Read-only audit. No BLE or CoAP traffic was sent to any watch during this analysis.

---

## 1. Executive Summary

**No conclusively safe additional read-only CoAP operation is established for the Fitbit Inspire 3 from the evidence currently on disk.**

The only CoAP read operation already exercised on the Inspire 3 is `GET /.well-known/core`, which returned **4.04 Not Found**. Every other candidate path fails at least one of the required evidence gates: either the path was never executed on the target firmware, no response parser exists, the semantics are not truly read-only, or the path is specific to a different device (Fitbit Air / Google Health integration) and not confirmed on Inspire 3.

**Decision:** Do not add a new automatic or default read-only CoAP path to BlueBit on the basis of existing evidence. The next step should be a controlled, consent-gated device test rather than a further source-only inference.

---

## 2. Evidence Examined

### 2.1 BlueBit local reports

| Report | Relevance |
|--------|-----------|
| `reports/coap/CoAP_ReadOnly_Probe_Implementation_Notes.md` | BlueBit probe sends `GET /.well-known/core`; observed 4.04 on Inspire 3; path was later made configurable, default unchanged. |
| `reports/coap/reference_coap_readonly_audit.md` | Reference bridge audit: lists `/.well-known/core`, `/alarms`, `/device/info`, `/pair/status`, `/pair/display`, `/bond/control`, `/sync/status`, `/sync/config`; only `/alarms` has a parser. |
| `reports/coap/next_coap_endpoint_investigation.md` | Concludes no automatic follow-up path is justified; recommends manual consent-gated selector. |
| `reports/coap/coap_44_response_investigation.md` | Confirms Inspire 3 response was standard `4.04 Not Found`; path/options construction is correct. |
| `reports/coap/bluebit_coap_api_audit.md` | BlueBit Kotlin wrappers support `GET`, but no existing diagnostic sends read-only CoAP. |
| `reports/apk/Fitbit_Whole_APK_Onboarding_Audit.md` | Reconstructed official Fitbit app flow; post-onboarding GET resources (`md/606`, `liveactivity`, `sync/status`, `sync/config`) require MobileData PSK, not bootstrap PSK. |
| `reports/apk/device-type-catalog-analysis.md` | No Inspire 3 / FB515 identifiers found in APK; catalogue is server-fetched. |
| `reports/apk/Fitbit_Pairing_Deep_Research.md` | Post-onboarding reads require `/sync/dump` → backend → `/sync/response` relay; blocked without backend secret. |
| `reports/general/fitbit_air_bridge_summary.md` | Fitbit Air bridge validated on one Air only; `/settings` haptics protocol is Air-specific, unlikely to work on Inspire 3. |
| `reports/other/EXPERIMENT_REPORT.md` | Mock server experiment; no device-side CoAP read tested. |
| `reports/other/Inspire3_Gattlink_Startup_Investigation.md` | Gattlink startup sequence confirmed, but does not address CoAP resources. |
| `reports/other/inspire3-goldengate-init-report.md` | Gattlink-only stack initialization; no DTLS/CoAP. |
| `reports/dtls/dtls_error_minus_10601_investigation.md` | DTLS hub/server-mode error; not applicable to client bootstrap used by read-only probe. |
| `reports/other/fitbit_bugreport.zip` | Searched main log; no Fitbit/CoAP/GoldenGate log lines found; no extracted notes exist. |

### 2.2 Reference project source

Audited `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate`:

- `app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt` — developer test UI; contains `READ_ONLY_PROBE_PATHS` and `probeGet()`, but `runReadOnlyAuthorizationProbe()` has **no call site** in production or test automation.
- `app/src/main/kotlin/com/fitbit/goldengatehost/AirAlarmProtocol.kt` — protobuf parser for `GET /alarms` response.
- `app/src/main/kotlin/com/fitbit/goldengatehost/AirDirectSettingsProtocol.kt` — `/settings` protobuf codec; read is implemented as `PUT /settings` with a request body.
- `app/src/main/kotlin/com/fitbit/goldengatehost/AirHapticPatternExecutor.kt` — production bridge uses `PUT /settings` (semantic read) and `POST /settings` (write).
- `app/src/main/kotlin/com/fitbit/goldengatehost/AirBridgeRuntime.kt`, `AirBridgeDeviceSettings.kt` — no device-model or firmware compatibility checks.
- Native GoldenGate source (`xp/`, `GoldenGateBindings/src/main/cpp/`) — no hard-coded resource path tables for device info, sync, or pair status.

---

## 3. Candidate Read Operations Considered

### 3.1 `GET /.well-known/core`

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ | `CoapActivity.kt:681`, `CoapReadOnlyProbe.kt:71` |
| Request payload (none) | ✅ | GET, no body |
| Response model / parser | ⚠️ | Link-format text only; informal truncation, no formal parser |
| Read-only semantics | ✅ | Standard CoAP resource discovery |
| Successful use on Inspire 3 | ❌ | Returned **4.04 Not Found** on Inspire 3 (`coap_44_response_investigation.md`) |

**Verdict:** Already implemented and observed to fail on the target device. Not a useful next read.

---

### 3.2 `GET /alarms`

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ | `CoapActivity.kt:589`, `CoapActivity.kt:682` |
| Request payload (none) | ✅ | GET, no body |
| Response model / parser | ✅ | `AirAlarmProtocol.parseDeviceAlarms()` returns `List<AlarmRecord>` |
| Read-only semantics | ✅ | GET only; does not create/modify/delete alarms |
| Successful use on Inspire 3 | ❌ | `getAlarms()` is only called from `runAlarmAutoDeleteTest()`, which has **no call site**. Never observed on Inspire 3. |
| Additional concern | ⚠️ | Response contains user alarm times/settings (moderate privacy risk). |

**Verdict:** Rejected. The parser exists but the path is dead code in the reference project and has not been exercised on Inspire 3 firmware. The response model is Fitbit Air/Google Health specific and may not match Inspire 3 alarm protobuf layout.

---

### 3.3 `GET /device/info`

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ listed | `CoapActivity.kt:683` |
| Request payload (none) | ✅ | GET, no body |
| Response model / parser | ❌ | No parser in audited Kotlin sources |
| Read-only semantics | ✅ in principle | Listed as GET |
| Successful use on Inspire 3 | ❌ | No execution evidence; no call site |

**Verdict:** Rejected. Path appears only in a static probe list. No evidence it was ever executed or parsed in the reference bridge, and no evidence Inspire 3 exposes it over bootstrap PSK.

---

### 3.4 `GET /pair/status`

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ listed | `CoapActivity.kt:684` |
| Request payload (none) | ✅ | GET, no body |
| Response model / parser | ❌ | No parser in audited Kotlin sources |
| Read-only semantics | ⚠️ | Name associates with pairing state; unknown side effects |
| Successful use on Inspire 3 | ❌ | No execution evidence |

**Verdict:** Rejected. No parser, no execution, and the name suggests it may participate in pairing state management rather than being a passive metadata read.

---

### 3.5 `GET /pair/display`

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ listed | `CoapActivity.kt:685` |
| Request payload (none) | ✅ | GET, no body |
| Response model / parser | ❌ | No parser in audited Kotlin sources |
| Read-only semantics | ⚠️ | Official APK implements `pair/display` as **PUT** (`SHOW_CODE` / `CLEAR_CODE`); a GET interpretation is unverified |
| Successful use on Inspire 3 | ❌ | No execution evidence |

**Verdict:** Rejected. The same resource is a write/state-changing resource in the official app (`PairCommandsHandler.java`). A GET could still trigger UI side effects (e.g., display or clear pairing code).

---

### 3.6 `GET /bond/control`

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ listed | `CoapActivity.kt:686` |
| Request payload (none) | ✅ | GET, no body |
| Response model / parser | ❌ | No parser in audited Kotlin sources |
| Read-only semantics | ❌ | Official APK implements `bond/control` as **PUT** with `operationTimeout`; BlueBit observed 4.01 on PUT. A GET could affect bond state. |
| Successful use on Inspire 3 | ❌ | No GET execution evidence |

**Verdict:** Rejected. High state-change risk; resource is explicitly a bond-management command surface in the official app.

---

### 3.7 `GET /sync/status`

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ listed | `CoapActivity.kt:687` |
| Request payload (none) | ✅ | GET, no body |
| Response model / parser | ❌ | No parser in audited Kotlin sources; `SyncRequestOuterClass` exists but no GET response parser found |
| Read-only semantics | ✅ in principle | Listed as GET |
| Successful use on Inspire 3 | ❌ | No execution evidence |

**Verdict:** Rejected. Path appears only in a static list. The official APK uses sync resources after server onboarding with MobileData PSK, not bootstrap PSK.

---

### 3.8 `GET /sync/config`

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ listed | `CoapActivity.kt:688` |
| Request payload (none) | ✅ | GET, no body |
| Response model / parser | ❌ | No parser in audited Kotlin sources |
| Read-only semantics | ✅ in principle | Listed as GET |
| Successful use on Inspire 3 | ❌ | No execution evidence |

**Verdict:** Rejected. Same reasoning as `/sync/status`.

---

### 3.9 `PUT /settings` (semantic read of haptics intensity)

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ | `CoapActivity.kt:130`, `AirHapticPatternExecutor.kt:88` |
| Request payload | ✅ | `0x0A 0x01 0x01` (`SETTING_TYPE_HAPTICS`) |
| Response model / parser | ✅ | `AirDirectSettingsProtocol.parseHapticsIntensity()` |
| Read-only semantics | ❌ | Uses PUT, not GET; although payload contains no value, the method is write-capable |
| Successful use on Inspire 3 | ❌ | Path/protocol is Fitbit Air / Google Health specific; `fitbit_air_bridge_summary.md` states it is unlikely to work on Inspire 3 without changes |

**Verdict:** Rejected. Not a true read-only CoAP operation, and the protocol is device-specific to Fitbit Air.

---

### 3.10 `GET helloworld`

| Gate | Status | Evidence |
|------|--------|----------|
| Exact path & method | ✅ | `BsdCoapActivity.kt:128` |
| Request payload (none) | ✅ | GET, no body |
| Response model / parser | ✅ | Returns `"Hello world!"` text/plain |
| Read-only semantics | ✅ | Demo local server handler |
| Successful use on Inspire 3 | ❌ | Local BSD-socket demo server resource; not sent over BLE/DTLS to a watch |

**Verdict:** Rejected. Not applicable to BLE/DTLS/Inspire 3.

---

## 4. Conclusively Safe Read Operation Found

**None.** No candidate beyond the already-implemented `GET /.well-known/core` satisfies all five required gates for the Inspire 3:

1. Exact CoAP path and method.
2. Request payload (usually none for GET).
3. Response model or parser.
4. Read-only semantics (no side effects).
5. Successful use on the exact Inspire 3 or clearly identified compatible firmware.

The closest candidate, `GET /alarms`, has a parser and is semantically read-only, but it fails the critical fifth gate: it has never been observed to succeed on Inspire 3 and is dead code in the reference project.

---

## 5. Remaining Unknowns

1. **Inspire 3 CoAP resource table.** The firmware may expose a different set of resources than the Fitbit Air reference list; `/.well-known/core` returning 4.04 suggests the resource-discovery convention used by the reference bridge does not apply.
2. **Bootstrap-PSK vs. MobileData-PSK authorization boundary.** Some resources (`md/606`, `liveactivity`, `sync/status`, `sync/config`) are documented as post-onboarding and require the per-device MobileData PSK, which BlueBit does not possess.
3. **Alarm protobuf layout on Inspire 3.** Even if `GET /alarms` were accepted, the response format may differ from the Air/Google Health `DeviceAlarms` message parsed by `AirAlarmProtocol`.
4. **Side effects of `GET` on pair/bond/sync paths.** The reference list includes paths whose names associate with stateful operations; a GET may still trigger firmware behavior (e.g., UI display, state transitions).
5. **Bugreport utility.** The archived `fitbit_bugreport.zip` contains no Fitbit/CoAP/GoldenGate log lines, so it contributes no direct evidence.

---

## 6. Recommended Next Action

**Do not add a new default or automatic read-only CoAP path based on existing evidence.** Instead:

1. **Use the existing configurable probe for a controlled device test.** The current `CoapReadOnlyProbe` accepts an optional path while still defaulting to `/.well-known/core`. This allows a single, operator-initiated, logged attempt against a candidate path when the Inspire 3 is available.
2. **If a device test is performed, start with the lowest-risk path:** `GET /device/info` (no parser, but low privacy/state-change risk) or `GET /alarms` (has parser, but returns user data). Capture the response code and raw body for analysis. Do **not** probe `/pair/display`, `/bond/control`, or `/sync/*` first.
3. **If no device is available, wait for more evidence.** Additional evidence could come from:
   - A fresh bugreport or HCI snoop capture taken while the official Fitbit app reads a safe resource on the Inspire 3.
   - Extraction of MobileData PSK after official onboarding, enabling post-onboarding reads.
   - Gadgetbridge or community reports documenting Inspire 3-specific read paths.

---

## 7. Source Citations

- BlueBit read-only probe: `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt:71`
- Probe logic & path validation: `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt:37-45`
- Reference `READ_ONLY_PROBE_PATHS`: `fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt:681-689`
- Reference `probeGet()` helper: `fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt:492-516`
- Reference `/alarms` GET + parser: `fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt:589` and `AirAlarmProtocol.kt:42-52`
- Reference `/settings` PUT read: `fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt:126-165` and `AirDirectSettingsProtocol.kt`
- Official APK `pair/display` PUT & `bond/control` PUT: `reports/apk/Fitbit_Whole_APK_Onboarding_Audit.md` §3.1, §4.1, §8.1
- Inspire 3 `/.well-known/core` 4.04 observation: `reports/coap/coap_44_response_investigation.md`
- Air bridge device-specific caveat: `reports/general/fitbit_air_bridge_summary.md` §Caveats
- Bugreport searched and empty of relevant logs: audit step in §2.1 above

---

*End of audit.*
