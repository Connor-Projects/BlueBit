# BlueBit Probe CoAP Read-Only: `code=4.4` Investigation

## Summary

The log line `CoAP response code=4.4 length=0` is **a formatting bug in BlueBit's logger and result object**, not a non-standard CoAP response code. The actual response from the Fitbit Inspire 3 is the standard **4.04 Not Found** (`responseClass = 4`, `detail = 4`).

The URI path/options construction is correct and matches the reference bridge, so the 4.04 is a genuine CoAP error returned by the watch firmware — most likely because `/.well-known/core` is not exposed (or not authorized) on the connected firmware build.

## 1. Response-code representation in BlueBit

### Source files

- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/ResponseCode.kt` (lines 3-5, 18)
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/IncomingResponse.kt` (line 3-4)
- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/SingleCoapResponseListener.kt` (line 48-52)

### What `getResponseCode()` returns

```kotlin
class ResponseCode(
    val responseClass: Byte,
    val detail: Byte
)
```

- `responseClass`: the CoAP class (2 = success, 4 = client error, 5 = server error).
- `detail`: the CoAP detail code within that class.

The constant for **Not Found** is defined as:

```kotlin
@JvmField val notFound = ResponseCode(4, 4)
```

So a 4.04 response is represented internally as `ResponseCode(responseClass = 4, detail = 4)`.

### How the listener logs it

`SingleCoapResponseListener.onNext` logs the raw fields separately:

```kotlin
Log.i(TAG, "onNext responseClass=${code.responseClass} detail=${code.detail} dataLen=${rawResponseMessage.data.size}")
```

This would print `responseClass=4 detail=4`, which is unambiguous.

## 2. Request path construction

### Source files

- `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OutgoingRequestBuilder.kt` (lines 15-24)
- `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt` (line 449)

### How `OutgoingRequestBuilder` works

```kotlin
init {
    addPathToOptions(path)
}

private fun addPathToOptions(path: String) {
    path.split("/").filter { it.isNotEmpty() }.forEach { segment ->
        options.add(UriPathOption(segment))
    }
}
```

For `OutgoingRequestBuilder(".well-known/core", Method.GET)`:
- `path.split("/")` → `[".well-known", "core"]`
- Both segments are non-empty, so two `UriPathOption`s are added:
  - `UriPathOption(".well-known")`
  - `UriPathOption("core")`

No leading slash is required; the builder filters empty segments, so `/.well-known/core` would produce the exact same options.

This matches the CoAP URI encoding rules: `/.well-known/core` is encoded as Uri-Path options `.well-known` and `core`.

## 3. Reference bridge comparison

### Source files

- `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/CoapActivity.kt`
  - `probeGet()` / GET request construction: lines 492-516
  - `READ_ONLY_PROBE_PATHS`: lines 678-686
  - `formatCode()`: line 654

### Request construction

The reference bridge uses the same `OutgoingRequestBuilder` class and passes paths with a leading slash:

```kotlin
private val READ_ONLY_PROBE_PATHS = listOf(
    "/.well-known/core",
    "/alarms",
    ...
)

private fun probeGet(stackService: CoapEndpoint, path: String): Single<String> {
    val request = OutgoingRequestBuilder(path, Method.GET)
        .expectSuccess(false)
        .maxResendCount(0)
        .build()
    ...
}
```

Because `addPathToOptions` strips the leading empty segment, the resulting CoAP Uri-Path options are identical to BlueBit's: `.well-known`, `core`.

### Response-code formatting

The reference bridge pads the detail to two digits:

```kotlin
private fun formatCode(response: IncomingResponse): String =
    "${response.responseCode.responseClass}.${response.responseCode.detail.toInt().toString().padStart(2, '0')}"
```

So `ResponseCode(4, 4)` is printed as `4.04`, not `4.4`.

### Response handling differences

| Aspect | BlueBit | Reference bridge |
|---|---|---|
| Path string passed to builder | `.well-known/core` | `/.well-known/core` |
| Uri-Path options produced | `.well-known`, `core` | `.well-known`, `core` |
| Code formatting | `"$class.$detail"` → `4.4` | `detail.padStart(2, '0')` → `4.04` |
| Timeout | `withTimeoutOrNull(COAP_TIMEOUT_MS)` | RxJava timeout (implicit) |
| Expect success | not set (default `false`) | `.expectSuccess(false)` |
| Max resend | default `-1` | `.maxResendCount(0)` |

The only material formatting/UX difference is the missing zero-pad in BlueBit.

## 4. Cause determination

Based on the source evidence, the observed `code=4.4` is:

**a) A formatting bug in BlueBit's logger and result object.**

The true response code is **4.04 Not Found** (`ResponseCode(responseClass = 4, detail = 4)`).

Evidence:
- `ResponseCode.kt:18` defines `notFound = ResponseCode(4, 4)`.
- `CoapReadOnlyProbe.kt:467` formats the code as `"${responseCode.responseClass}.${responseCode.detail}"`. For `detail = 4` this prints `4.4` instead of `4.04`.
- `CoapReadOnlyProbeLogic.kt:27` defines `CoapResponseCode.toString()` with the same pattern, so the persisted `responseCode` field also displays as `4.4`.
- `CoapActivity.kt:654` (reference bridge) uses `detail.toInt().toString().padStart(2, '0')`, confirming the conventional two-digit representation.

`4.4` is **not** a valid CoAP response-code notation. CoAP detail codes are written with two digits (e.g., 4.00, 4.01, 4.02, 4.03, 4.04, 4.05).

The 4.04 itself is **a genuine error from the watch**, not a decoding artifact:
- DTLS is established (`state=2`).
- The CoAP request is successfully sent and a CoAP response is received.
- A 4.04 response means the CoAP layer understood the request but the resource does not exist or is not accessible. If the path options were malformed in a way the CoAP parser rejected, the watch would more likely return 4.00 Bad Request or 4.02 Bad Option.
- The zero-length body is consistent with a 4.04 Not Found response.

Therefore the correct classification is **(a) formatting bug**, and separately the watch is genuinely returning **4.04 Not Found**.

## 5. Recommended fix

1. **Zero-pad the detail to two digits in the probe logger.**
   - File: `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt`
   - Line: 467
   - Change:
     ```kotlin
     val codeString = "${responseCode.responseClass}.${responseCode.detail.toInt().toString().padStart(2, '0')}"
     ```

2. **Zero-pad the detail in the shared result type.**
   - File: `app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt`
   - Lines: 26-27
   - Change `CoapResponseCode.toString()` to:
     ```kotlin
     override fun toString(): String =
         "$responseClass.${detail.toInt().toString().padStart(2, '0')}"
     ```

3. **Update existing desktop tests** that encode the old format.
   - File: `app/src/desktopTest/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeTest.kt`
   - Lines: 35, 79, 93
   - Change expected strings from `"2.5"`, `"2.5"`, `"4.1"` to `"2.05"`, `"2.05"`, `"4.01"`.

4. Consider aligning `maxResendCount` and `expectSuccess` with the reference bridge if desired, but this is not required to fix the formatting bug.

## 6. Additional tests to add

- **Response-code formatting test:**
  ```kotlin
  assertEquals("4.04", CoapResponseCode(4, 4).toString())
  assertEquals("2.05", CoapResponseCode(2, 5).toString())
  assertEquals("4.01", CoapResponseCode(4, 1).toString())
  ```

- **4.04 Not Found handling test:**
  Verify that `processResponse(CoapResponseCode(4, 4), "")` returns:
  - `coapSuccess = false`
  - `responseCode = "4.04"`
  - `error` containing `"4.04"`

- **Path-option construction test (Android instrumented):**
  Build `OutgoingRequestBuilder(".well-known/core", Method.GET)` and assert the options list contains exactly `UriPathOption(".well-known")` and `UriPathOption("core")`.

- **Reference parity test:**
  Assert that building with `".well-known/core"` and `"/.well-known/core"` produces identical Uri-Path options, documenting that the leading slash is irrelevant.

## Source reference summary

| Claim | File | Line(s) |
|---|---|---|
| `ResponseCode` has `responseClass` and `detail` bytes | `ResponseCode.kt` | 3-5 |
| `notFound = ResponseCode(4, 4)` | `ResponseCode.kt` | 18 |
| Listener logs raw class/detail separately | `SingleCoapResponseListener.kt` | 48-52 |
| `addPathToOptions` splits and filters empty segments | `OutgoingRequestBuilder.kt` | 20-24 |
| BlueBit builds request without leading slash | `CoapReadOnlyProbe.kt` | 449 |
| BlueBit formats code as `$class.$detail` | `CoapReadOnlyProbe.kt` | 467 |
| Shared `CoapResponseCode.toString()` same bug | `CoapReadOnlyProbeLogic.kt` | 26-27 |
| Reference bridge pads detail to 2 digits | `CoapActivity.kt` (reference) | 654 |
| Reference bridge uses `/.well-known/core` | `CoapActivity.kt` (reference) | 680 |
| Existing tests expect old format | `CoapReadOnlyProbeTest.kt` | 35, 79, 93 |
