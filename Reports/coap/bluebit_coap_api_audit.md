# BlueBit CoAP API Audit

**Date:** 2026-10-10  
**Scope:** Existing CoAP-related Kotlin code in `G:/Dev/BlueBit/BlueBitApp`  
**Goal:** Determine whether the current BlueBit API supports sending a safe read-only CoAP diagnostic query.

---

## 1. Executive Summary

BlueBit has a working, thin Kotlin wrapper around Fitbit's GoldenGate native CoAP stack. The wrapper supports all four standard CoAP request methods, request/response body handling, URI path options, and low-level CoAP options (content format, accept, query params, blockwise, etc.).

**No existing BlueBit diagnostic sends a read-only CoAP request.** The only diagnostic that actually sends CoAP requests is `FitbitGoldenGateDiagnostic`, and it only sends state-changing `PUT` requests (`bond/control`, `pair/display`). `DtlsBootstrapProbe` attaches a `CoapEndpoint` but intentionally sends no application requests.

A read-only `GET` request can be built and sent today with the existing API. There is no dedicated timeout/cancellation at the request level, but the call is `suspend`-based and can be wrapped in `withTimeoutOrNull` by the caller. Cancellation will detach the deferred but will not proactively cancel the native listener unless `cleanupNativeListener()` is invoked.

---

## 2. Files Inspected

| # | Path | Notes |
|---|------|-------|
| 1 | `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpoint.kt` | Main entry point for sending requests. |
| 2 | `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpointBuilder.kt` | **Not present in the repository.** Listed in task but does not exist. |
| 3 | `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/*.kt` | Request/response/data model classes. |
| 4 | `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/FitbitGoldenGateDiagnostic.kt` | Only BlueBit diagnostic that sends CoAP requests. |
| 5 | `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt` | Attaches endpoint, sends no CoAP requests. |
| 6 | Other files under `app/src/androidMain/kotlin/bluebit/` | Searched; only the two files above import CoAP classes. |

---

## 3. CoAP Request Builder API

### 3.1 Request construction

Builder class: `com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder`

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OutgoingRequestBuilder.kt:3`

```kotlin
class OutgoingRequestBuilder(
    private val path: String,
    private val method: Method
) : BaseOutgoingMessageBuilder<OutgoingRequest>()
```

Fluent API:

| Method | Return type | Source | Purpose |
|--------|-------------|--------|---------|
| `body(ByteArray)` | `OutgoingRequestBuilder` | `OutgoingRequestBuilder.kt:17` | Attach a protobuf/raw payload. |
| `option(Option)` | `OutgoingMessageBuilder<T>` (inherited) | `BaseOutgoingMessageBuilder.kt:31` | Add arbitrary CoAP options. |
| `ackTimeout(Int)` | `OutgoingRequestBuilder` | `OutgoingRequestBuilder.kt:28` | ACK timeout in ms (native). |
| `expectSuccess(Boolean)` | `OutgoingRequestBuilder` | `OutgoingRequestBuilder.kt:33` | Native expectation flag. |
| `forceNonBlockwise(Boolean)` | `OutgoingRequestBuilder` | `OutgoingRequestBuilder.kt:38` | Force non-blockwise transfer. |
| `maxResendCount(Int)` | `OutgoingRequestBuilder` | `OutgoingRequestBuilder.kt:43` | Max retransmissions. |
| `build()` | `OutgoingRequest` | `OutgoingRequestBuilder.kt:48` | Build immutable request. |

Path handling: constructor automatically splits the path on `/` and adds a `UriPathOption` per non-empty segment (`OutgoingRequestBuilder.kt:12-15`).

### 3.2 Base builder

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/BaseOutgoingMessageBuilder.kt`

```kotlin
abstract class BaseOutgoingMessageBuilder<T : Message> : OutgoingMessageBuilder<T> {
    protected val options = LinkedList<Option>()
    protected var body: OutgoingBody = EmptyOutgoingBody()
    var progressObserver: ProgressObserver = NoOpProgressObserver

    override fun body(bArr: ByteArray): OutgoingMessageBuilder<T> {
        this.body = BytesArrayOutgoingBody(bArr)
        return this
    }

    override fun option(option: Option): OutgoingMessageBuilder<T> {
        options.add(option)
        return this
    }

    fun progressObserver(observer: ProgressObserver): OutgoingMessageBuilder<T>
}
```

### 3.3 Request interface

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OutgoingRequest.kt`

```kotlin
interface OutgoingRequest : OutgoingMessage, BaseRequest {
    fun getAckTimeout(): Int
    fun getExpectSuccess(): Boolean
    fun getForceNonBlockwise(): Boolean
    fun getMaxResendCount(): Int
}
```

`BaseRequest` (`BaseRequest.kt:3`):

```kotlin
interface BaseRequest {
    fun getMethod(): Method
    fun getProgressObserver(): ProgressObserver
}
```

### 3.4 Available methods

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/Method.kt`

```kotlin
enum class Method(val value: Byte) {
    GET(1),
    POST(2),
    PUT(3),
    DELETE(4);
}
```

All four standard CoAP methods are available. `GET` is the read-only method.

### 3.5 Options support

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OptionNumber.kt`

Supported option numbers:

| Option | Number | Kotlin class |
|--------|--------|--------------|
| If-Match | 1 | `IfMatchOption` |
| ETag | 4 | `EtagOption` |
| If-None-Match | 5 | `IfNoneMatchOption` |
| Location-Path | 8 | `LocationPathOption` |
| URI-Path | 11 | `UriPathOption` |
| Content-Format | 12 | `ContentFormatOption` |
| Max-Age | 14 | `MaxAgeOption` |
| URI-Query | 15 | `UriQueryOption` |
| Accept | 17 | `AcceptOption` |
| Location-Query | 20 | `LocationQueryOption` |
| Block2 | 23 | `Block2Option` |
| Block1 | 27 | `Block1Option` |
| Start-Offset | 2048 | `StartOffset` |

Convenience `OptionsBuilder` exists at `OptionsBuilder.kt:8` to build option lists by number.

---

## 4. Sending Requests

### 4.1 CoapEndpoint.sendRequest

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpoint.kt:103`

```kotlin
suspend fun sendRequest(request: OutgoingRequest): IncomingResponse = withContext(Dispatchers.IO) {
    val deferred = CompletableDeferred<IncomingResponse>()
    val listener = SingleCoapResponseListener { result ->
        result.fold(
            onSuccess = { deferred.complete(it) },
            onFailure = { deferred.completeExceptionally(it) }
        )
    }

    val forceNonBlockwise = request.getForceNonBlockwise()
    val result = if (forceNonBlockwise) {
        responseFor(thisPointerWrapper, request, listener)
    } else {
        responseForBlockwise(thisPointerWrapper, request, listener)
    }

    if (result.resultCode < 0) {
        listener.cleanupNativeListener()
        throw RuntimeException("responseFor failed: ${result.resultCode}")
    }

    deferred.await()
}
```

The method:
- Runs on `Dispatchers.IO`.
- Internally creates a `SingleCoapResponseListener`.
- Calls native `responseFor` or `responseForBlockwise` depending on `forceNonBlockwise`.
- Returns the `IncomingResponse` when the native callback fires.

### 4.2 Native methods

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpoint.kt:63-75`

```kotlin
private external fun responseFor(
    j: Long,
    outgoingRequest: OutgoingRequest,
    coapResponseListener: CoapResponseListener
): ResponseForResult

private external fun responseForBlockwise(
    j: Long,
    outgoingRequest: OutgoingRequest,
    coapResponseListener: CoapResponseListener
): ResponseForResult
```

### 4.3 Endpoint interface (RxJava-style wrapper)

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/Endpoint.kt`

```kotlin
interface Endpoint {
    fun responseFor(outgoingRequest: OutgoingRequest): Any
}
```

This is a minimal interface; the concrete implementation used in BlueBit is `CoapEndpoint`.

---

## 5. Receiving and Parsing Responses

### 5.1 Response listener

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/SingleCoapResponseListener.kt`

```kotlin
class SingleCoapResponseListener(
    private val onResult: (Result<IncomingResponse>) -> Unit
) : CoapResponseListener {
    private var nativeRef: Long = 0
    private val completed = AtomicBoolean(false)

    private external fun cancelResponse(nativeResponseListenerReference: Long): Int

    override fun cleanupNativeListener()
    override fun isComplete(): Boolean
    override fun onAck()
    override fun onComplete()
    override fun onError(i: Int, str: String)
    override fun onNext(rawResponseMessage: RawResponseMessage)
    override fun setNativeListenerReference(j: Long)
}
```

`onNext` converts a `RawResponseMessage` into an anonymous `IncomingResponse`:

```kotlin
override fun onNext(rawResponseMessage: RawResponseMessage) {
    completed.set(true)
    val response = object : IncomingResponse {
        override fun getBody(): IncomingBody = object : IncomingBody {
            override fun asData(): ByteArray = rawResponseMessage.data
        }
        override fun getExtendedError() = null
        override fun getOptions(): LinkedList<Option> = rawResponseMessage.options
        override fun getResponseCode(): ResponseCode = rawResponseMessage.responseCode
    }
    onResult(Result.success(response))
    cleanupNativeListener()
}
```

### 5.2 Response interfaces

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/IncomingResponse.kt`

```kotlin
interface IncomingResponse : IncomingMessage, BaseResponse {
    fun getExtendedError(): ExtendedError?
}
```

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/BaseResponse.kt`

```kotlin
interface BaseResponse {
    fun getResponseCode(): ResponseCode
}
```

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/IncomingMessage.kt`

```kotlin
interface IncomingMessage : Message {
    fun getBody(): IncomingBody
}
```

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/IncomingBody.kt`

```kotlin
interface IncomingBody {
    fun asData(): ByteArray
}
```

### 5.3 Response codes

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/ResponseCode.kt`

Defined constants include:

- Success: `created(2,1)`, `deleted(2,2)`, `valid(2,3)`, `changed(2,4)`, `content(2,5)`, `continueCode(2,31)`
- Client errors: `badRequest(4,0)` through `unsupportedContentFormat(4,15)`
- Server errors: `internalServerError(5,0)` through `proxyingNotSupported(5,5)`

Response class check pattern used in BlueBit:

```kotlin
val rc = response.getResponseCode()
val rspClass = rc.responseClass.toInt()
val rspDetail = rc.detail.toInt()
if (rspClass == 2) { /* success */ }
```

**Source:** `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/FitbitGoldenGateDiagnostic.kt:506-508`

---

## 6. Timeouts and Cancellation

### 6.1 Request-level timeout

There is **no built-in timeout inside `CoapEndpoint.sendRequest`**. The method awaits a `CompletableDeferred` indefinitely until the native listener calls back. A caller can wrap the call in Kotlin's `withTimeoutOrNull`:

```kotlin
val response = withTimeoutOrNull(10_000L) {
    endpoint.sendRequest(request)
}
```

### 6.2 Native cancellation

`SingleCoapResponseListener` exposes a native `cancelResponse(nativeRef)` method, but it is only called in two places:

1. When `responseFor` returns a negative result code (`CoapEndpoint.kt:118`).
2. Inside the listener itself after success or error (`SingleCoapResponseListener.kt:31-34`, `46-47`).

If the Kotlin coroutine is cancelled while `deferred.await()` is pending, the native listener is **not** automatically cleaned up by `sendRequest`. The caller would need to hold a reference to the listener and call `cleanupNativeListener()` manually. However, `sendRequest` does not expose the listener, so native cleanup on coroutine cancellation is not supported by the current wrapper.

### 6.3 Diagnostic-level timeouts

- `FitbitGoldenGateDiagnostic.EXPERIMENT_TIMEOUT_MS = 60_000L` (`FitbitGoldenGateDiagnostic.kt:48`)
- `DtlsBootstrapProbe.CONNECTION_TIMEOUT_MS = 15_000L` (`DtlsBootstrapProbe.kt:35`)
- `DtlsBootstrapProbe.SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L` (`DtlsBootstrapProbe.kt:36`)
- `DtlsBootstrapProbe.GATT_STEP_TIMEOUT_MS = 10_000L` (`DtlsBootstrapProbe.kt:37`)
- `DtlsProbeStateMachine.DEFAULT_HANDSHAKE_TIMEOUT_MS = 20_000L` (`DtlsProbeStateMachine.kt:42`)

---

## 7. Existing Diagnostics and CoAP Usage

### 7.1 DtlsBootstrapProbe — sends no CoAP requests

**Source:** `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt`

This probe creates and attaches a `CoapEndpoint` but explicitly logs that no requests will be sent:

```kotlin
val endpoint = CoapEndpoint()
coapEndpoint = endpoint
endpoint.attach(newStack)
Log.i(TAG, "CoapEndpoint attached (no requests will be sent)")
```

**Line:** `DtlsBootstrapProbe.kt:356-359`

It returns as soon as the DTLS session is established (or times out after 20 s).

### 7.2 FitbitGoldenGateDiagnostic — sends state-changing CoAP requests

**Source:** `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/FitbitGoldenGateDiagnostic.kt`

Helper method:

```kotlin
private suspend fun sendCoapRequest(
    endpoint: CoapEndpoint,
    path: String,
    method: Method,
    payload: ByteArray,
    experimentName: String
): IncomingResponse? {
    val request = OutgoingRequestBuilder(path, method)
        .body(payload)
        .forceNonBlockwise(true)
        .build()
    return try {
        val response = endpoint.sendRequest(request)
        ...
    } catch (e: Exception) {
        null
    }
}
```

**Lines:** `498-515`

Three experiments are run sequentially (`sendBondControl`, line 520):

| Experiment | CoAP path | Method | Payload | Purpose |
|------------|-----------|--------|---------|---------|
| A_DIRECT | `bond/control` | `PUT` | `0x08 0x78` | Direct bond control. |
| B_SHOW_THEN_BOND | `pair/display` | `PUT` | `0x08 0x01` | Show pairing code. |
| B_SHOW_THEN_BOND | `bond/control` | `PUT` | `0x08 0x78` | Bond immediately after show. |
| C_SHOW_WAIT_BOND | `pair/display` | `PUT` | `0x08 0x01` | Show pairing code. |
| C_SHOW_WAIT_BOND | `bond/control` | `PUT` | `0x08 0x78` | Bond after 30 s delay. |

All existing CoAP traffic in BlueBit is `PUT` and state-changing.

---

## 8. Resource Paths and Methods Used in BlueBit Code

### 8.1 Hard-coded in BlueBit source (this repo)

| Path | Method | File | Lines | Context |
|------|--------|------|-------|---------|
| `bond/control` | `PUT` | `FitbitGoldenGateDiagnostic.kt` | 531, 557 | Bond control experiment. |
| `pair/display` | `PUT` | `FitbitGoldenGateDiagnostic.kt` | 537, 544 | Show pairing code experiment. |

### 8.2 Hard-coded in decompiled Fitbit APK (reference only, not BlueBit code)

The audit also searched the adjacent decompiled sources (`apk_analysis_8B01Fj/decompiled/sources/com/fitbit/`) to understand what read-only resources Fitbit uses:

| Path | Method | File | Notes |
|------|--------|------|-------|
| `/settings` | `PUT`/`POST` | `CoapActivity.java`, `AirHapticPatternExecutor.java` | Haptics settings read/write. |
| `/alarms` | `GET`/`POST` | `CoapActivity.java` | Alarm list read / create. |
| `/alarms/{id}` | `DELETE` | `CoapActivity.java` | Alarm delete. |
| `helloworld` | `GET` | `BsdCoapActivity.java` | BSD socket demo. |
| `bond/control` | `PUT` | `CoapBondControlResource.java` | Bond control (matches BlueBit). |
| `pair/display` | `PUT` | `PairCommandsHandler` | Show pairing code (matches BlueBit). |

These are **not** used by BlueBit code but demonstrate that the same native stack supports `GET` read-only requests elsewhere.

---

## 9. Assessment: Does the Existing API Support a Safe Read-Only Query?

**Yes, with minor caveats.**

A read-only diagnostic could be implemented today using:

```kotlin
val request = OutgoingRequestBuilder("/settings", Method.GET)
    .forceNonBlockwise(true)
    .build()

val response = withTimeoutOrNull(10_000L) {
    endpoint.sendRequest(request)
}

response?.let {
    val code = it.getResponseCode()
    val body = it.getBody().asData()
    // parse body
}
```

### Strengths

1. `Method.GET` is fully supported.
2. `OutgoingRequestBuilder` can build GET requests with no body.
3. `forceNonBlockwise(true)` avoids blockwise complexity for small reads.
4. `CoapEndpoint.sendRequest` is `suspend` and can be wrapped by `withTimeoutOrNull`.
5. Response body, response code, and options are all accessible.
6. `AcceptOption` / `ContentFormatOption` allow setting `Accept` / `Content-Format` if the resource requires protobuf.

### Caveats

1. **No automatic native listener cleanup on coroutine cancellation.** If the caller's timeout fires, the underlying native listener may still be active. For a short-lived diagnostic this is acceptable, but for production code the wrapper should expose the listener or register a `Job.invokeOnCancellation` handler.
2. **No parser abstraction.** The caller receives a raw `ByteArray` and must know the protobuf/message format.
3. **No request-level retry configuration exposed at the call site.** `maxResendCount` and `ackTimeout` exist on the builder but are not currently exercised by BlueBit diagnostics.
4. **No `CoapEndpointBuilder`.** The task listed this file but it does not exist in the repo; endpoints are constructed directly via `CoapEndpoint()`.

### Recommendation

The existing API is sufficient for a safe read-only diagnostic probe. Implement it as a new function in `DtlsBootstrapProbe` (or a new diagnostic class) after the DTLS session is established, using `Method.GET`, `forceNonBlockwise(true)`, and a caller-side `withTimeoutOrNull`. If the probe is meant to be robust, consider adding coroutine-cancellation cleanup to `CoapEndpoint.sendRequest` so the native listener is cancelled when the caller times out.

---

*End of audit.*
