# Next CoAP Endpoint Investigation — Inspire 3 Read-Only Diagnostic

## Executive Summary

After `GET /.well-known/core` returned **4.04 Not Found** on an Inspire 3, the reference Fitbit Air Notification Bridge source does **not** provide a clearly safer, already-executed GET alternative that is guaranteed read-only and low-privacy on this firmware.

Source audit shows:

* The reference bridge’s `READ_ONLY_PROBE_PATHS` list (`CoapActivity.kt:680`) contains eight paths, but the function that would send them (`runReadOnlyAuthorizationProbe`) has **no call site** in the current source. The same is true for `getAlarms` (`runAlarmAutoDeleteTest` is also uncalled).
* The only actually-executed GET in the host Kotlin sources is `helloworld` in `BsdCoapActivity.kt`, which is a local BSD-socket demo and irrelevant to Inspire 3.
* The production Air/notification-bridge runtime only uses `PUT /settings` (a semantic read of haptics intensity) and `POST /settings`/`POST/DELETE /alarms` in dead test code.

Therefore, the safest next step is **not to send another automatic CoAP request**, but to **improve BlueBit’s diagnostic so it preserves the 4.04 result and lets the operator manually choose a single follow-up read-only path with explicit consent and logging**.

---

## Candidate Ranking

| Path | Source file / line | Purpose / handler | Actually executed? | Read-only evidence | Privacy risk | State-change risk | Inspire 3 mention |
|------|-------------------|-------------------|-------------------|--------------------|--------------|-------------------|-------------------|
| `/.well-known/core` | `CoapActivity.kt:680` (list), `CoapReadOnlyProbe.kt:449` (BlueBit) | CoAP resource discovery; BlueBit logs truncated link-format list | Yes — by BlueBit’s existing probe | GET, no body | None | None | Observed returning **4.04** on Inspire 3 |
| `/alarms` GET | `CoapActivity.kt:589` | Lists device alarms; parsed by `AirAlarmProtocol.parseDeviceAlarms` | **No** — `getAlarms` is only called from `runAlarmAutoDeleteTest`, which has no call site | GET, no body | **Moderate** — returns user alarm times/settings | None (GET only) | None; Air-specific code |
| `/settings` PUT (semantic read) | `CoapActivity.kt:130`, `376`; `AirHapticPatternExecutor.kt:88` | Reads current haptics intensity via a `SettingsRequest` body | Yes — run on connect and during pattern execution | Source comments state the payload contains only `SETTING_TYPE_HAPTICS` and no value, so it cannot change intensity | Low (haptics level only) | Low, but method is PUT and therefore write-capable in principle; not a true GET | None; Air-specific code |
| `helloworld` GET | `BsdCoapActivity.kt:128` | Local BSD-socket demo resource | Yes — on button click in BSD CoAP activity | GET, no body | None | None | Not applicable to BLE/DTLS/Inspire 3 |
| `/device/info` | `CoapActivity.kt:683` (list only) | None in audited Kotlin sources | **No** | Listed as GET in `READ_ONLY_PROBE_PATHS` | Unknown, likely low (device metadata) | Unknown | None |
| `/pair/status` | `CoapActivity.kt:684` (list only) | None in audited Kotlin sources | **No** | Listed as GET in `READ_ONLY_PROBE_PATHS` | Unknown | Unknown | None |
| `/pair/display` | `CoapActivity.kt:685` (list only) | None in audited Kotlin sources | **No** | Listed as GET, but BlueBit/server docs associate `pair/display` with a PUT command | Unknown | Unknown (could trigger UI) | None |
| `/bond/control` | `CoapActivity.kt:686` (list only) | None in host Kotlin sources as GET; BlueBit uses it via `ServerPairingExperiment` concept | **No** as GET; known as a PUT/state-changing resource | Listed as GET, but widely referenced as a bond-management write | Unknown | **High** — bonding/pairing state | Inspire 3 mentioned in BlueBit logs around 4.01Unauthorized |
| `/sync/status` | `CoapActivity.kt:687` (list only) | None in audited Kotlin sources | **No** | Listed as GET | Unknown | Unknown | None |
| `/sync/config` | `CoapActivity.kt:688` (list only) | None in audited Kotlin sources | **No** | Listed as GET | Unknown | Unknown | None |

### Key take-aways from the table

1. **Only `/.well-known/core` has been observed on the device** — and it returned 4.04.
2. **Only `/alarms` has a real GET parser** in the reference source, but it is dead code and returns user data.
3. The remaining reference read-only paths are **just a list**; there is no runtime call, parser, or UI handler for them in the audited sources.
4. No path in the table is explicitly documented as supported on **Inspire 3** firmware.

---

## Comparison with BlueBit

| Capability | BlueBit current state |
|------------|----------------------|
| `GET /.well-known/core` | Implemented in `CoapReadOnlyProbe.kt` (`runProbe`) and wired to a diagnostic button in `MainActivity.kt:251`. |
| `/alarms` | Not implemented. |
| `/settings` | Not implemented. |
| `/bond/control` | Referenced in `MainActivity.kt:611` log and `ServerPairingExperiment.kt`, but only as a manual next step; BlueBit does not automatically send it. |
| Result persistence | `CoapReadOnlyResult` is returned and logged, but not retained in UI state or persisted across sessions. |
| Manual path selection | Not supported; the path is hard-coded to `.well-known/core` in `CoapReadOnlyProbeLogic.kt:62` and `CoapReadOnlyProbe.kt:449`. |

---

## Selected Next Step

**Implement a diagnostic improvement: add result capture and a manual, consent-gated single-path CoAP probe to BlueBit.**

This option:

* Requires **no new watch traffic** by itself.
* Preserves the evidence from the existing 4.04 response.
* Lets the operator decide whether to try a follow-up path, and which one, instead of auto-sending unverified requests.
* Keeps every future attempt logged, redacted, and reviewable.

### Why not pick a specific new path now?

* `/.well-known/core` already failed.
* `/alarms` GET has a parser but is dead code in the reference app and returns user alarm data.
* `/device/info` is low-privacy in principle, but the reference source provides **no evidence it was ever executed or parsed**.
* `/bond/control`, `/pair/display`, etc. have higher state-change risk and are only present as a static list.

A manual selector keeps the next query deliberate and documented rather than guessed.

---

## Recommended Code Change

### 1. Make the probe path configurable

**File:** `BlueBitApp/app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt`

Change:

```kotlin
fun createRequestSpec(): CoapRequestSpec =
    CoapRequestSpec(path = ".well-known/core", method = CoapReadOnlyMethod.GET)
```

to:

```kotlin
fun createRequestSpec(path: String = ".well-known/core"): CoapRequestSpec {
    require(path.isNotBlank()) { "CoAP path must not be blank" }
    return CoapRequestSpec(path = path.removePrefix("/"), method = CoapReadOnlyMethod.GET)
}
```

**Why:** Keeps the default behavior identical while allowing arbitrary read-only paths. Removing the leading `/` matches the existing convention (`OutgoingRequestBuilder` strips empty segments, so `.well-known/core` and `/.well-known/core` produce the same CoAP Uri-Path options).

### 2. Pass the path through the Android probe

**File:** `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt`

* Change `runProbe(device: BluetoothDevice)` to `runProbe(device: BluetoothDevice, path: String = ".well-known/core")`.
* Forward `path` into `sendCoapRequest(path)`.
* Update `sendCoapRequest()` to accept the path and build the request with it:

```kotlin
private suspend fun sendCoapRequest(path: String): CoapReadOnlyResponseSummary {
    ...
    val request = OutgoingRequestBuilder(path, Method.GET).build()
    Log.i(TAG, "Sending CoAP GET $path")
    ...
}
```

**Why:** The probe stays a single GET with no body; only the path changes.

### 3. Capture and surface the last result

**File:** `BlueBitApp/app/src/androidMain/kotlin/bluebit/MainActivity.kt`

* Add a state holder near the existing diagnostics:

```kotlin
private val lastCoapResult = mutableStateOf<CoapReadOnlyResult?>(null)
```

* In the existing `coap_readonly_probe` diagnostic action, store the result before logging:

```kotlin
val result = coapReadOnlyProbe.runProbe(device)
lastCoapResult.value = result
Log.i("BlueBit", "COAP_READONLY_PROBE result=$result")
```

* Render `lastCoapResult` in the diagnostics UI (e.g., under the diagnostic buttons) showing `responseCode`, `responseLength`, `resourceSummary`, and `error`. This preserves the 4.04 result without extra traffic.

**Why:** The current result is only in logcat; surfacing it makes the failure visible and comparable for the next manual attempt.

### 4. Add a manual path selector diagnostic

**File:** `BlueBitApp/app/src/androidMain/kotlin/bluebit/MainActivity.kt`

Add a second `ProviderDiagnostic` after the existing one:

```kotlin
ProviderDiagnostic(
    id = "coap_readonly_probe_manual",
    label = "Probe CoAP Read-Only (manual path)",
    action = { address ->
        try {
            val device = adapter.getRemoteDevice(address)
            // Show a dialog on the main thread
            showManualCoapPathDialog(device)
        } catch (e: Exception) {
            Log.e("BlueBit", "COAP_READONLY_PROBE_MANUAL failed: $address", e)
        }
    }
)
```

Implement `showManualCoapPathDialog(device: BluetoothDevice)` with:

* An `AlertDialog` containing an `EditText`.
* A dropdown/spinner of the reference list paths:
  `/.well-known/core`, `/device/info`, `/pair/status`, `/sync/status`, `/sync/config`, `/alarms`.
* A clear warning label: *"These paths are taken from the reference Air bridge but are not confirmed on Inspire 3. GET is read-only, but responses may contain user data. Tap Send only if you accept the risk."*
* On confirm, launch `lifecycleScope.launch { coapReadOnlyProbe.runProbe(device, chosenPath) }` and store the result in `lastCoapResult`.

**Why:** The operator chooses the next query; BlueBit never auto-probes beyond `/.well-known/core`. This satisfies the requirement to avoid guessing paths while making the next step repeatable and logged.

### 5. Add/update tests

**File:** `BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeTest.kt`

Add:

```kotlin
@Test
fun `request spec supports custom path and strips leading slash`() {
    val spec = CoapReadOnlyProbeLogic.createRequestSpec("/device/info")
    assertEquals("device/info", spec.path)
    assertEquals(CoapReadOnlyMethod.GET, spec.method)
}

@Test
fun `processResponse preserves raw non-link-format body for 4-04`() {
    val summary = CoapReadOnlyProbeLogic.processResponse(
        CoapResponseCode(4, 4),
        "not link format"
    )
    assertFalse(summary.coapSuccess)
    assertEquals("4.04", summary.responseCode)
    assertTrue(summary.resourceSummary.contains("not link format"))
}
```

**Why:** Verifies that the generalized probe remains safe for non-discovery responses and that raw payloads are captured for analysis.

### 6. Update documentation

**File:** `BlueBitApp/CoAP_ReadOnly_Probe_Implementation_Notes.md`

Add a section describing:

* The default probe still sends only `GET /.well-known/core`.
* A new manual-path mode is available for operator-initiated follow-up queries.
* The reference path list is unverified on Inspire 3 and may return 4.04/4.01.
* `/alarms` is excluded from the suggested default list because it returns user alarm data.

---

## Uncertainties and Risks

| Risk | Mitigation |
|------|------------|
| The reference `READ_ONLY_PROBE_PATHS` may be stale or Air-specific; Inspire 3 firmware may expose a different set of resources. | Do not auto-send any new path. Manual selector lets the operator stop after any unexpected response. |
| `GET` on a path such as `/pair/display` or `/bond/control` could still have side effects (e.g., showing a pairing code) even if the method is read-only. | Exclude `/bond/control` and `/pair/display` from the default suggested list; show an explicit warning for all manual choices. |
| Response bodies for unknown paths may contain user data or secrets. | Re-use the existing `redactSecrets()` logic; do not persist raw bodies, only the redacted summary. |
| `OutgoingRequestBuilder` behavior with custom paths could differ (empty segments, query strings). | Strip the leading `/` before passing to the builder; reject blank paths; do not allow query strings in the UI. |
| Adding UI state increases complexity. | Keep the change local to the existing diagnostics list and result class; no changes to DTLS/bootstrap logic. |

---

## Conclusion

The evidence does not justify an automatic follow-up CoAP query on the Inspire 3 right now. The best next step is to make BlueBit’s existing probe **configurable, observable, and operator-gated**, preserving the 4.04 finding and enabling deliberate, logged, single-shot exploration of the reference read-only paths when the operator chooses to proceed.
