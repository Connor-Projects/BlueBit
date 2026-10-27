# Kotlin DTLS Listener / PSK Resolver Investigation

Date: 2026-10-10
Project: `G:/Dev/BlueBit/BlueBitApp`

## 1. What resolvers are registered in `TlsKeyResolverRegistry`, and in what order?

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolverRegistry.kt:14-28`

```kotlin
private val firstResolver: HelloTlsKeyResolver = HelloTlsKeyResolver()
private var lastResolver: TlsKeyResolver = firstResolver

init {
    register(BootstrapTlsKeyResolver())
}

fun getResolvers(): HelloTlsKeyResolver = firstResolver

fun register(resolver: TlsKeyResolver) {
    lastResolver.setNext(resolver)
    lastResolver = resolver
}
```

**Answer:** The chain is built at object-initialization time:

1. `HelloTlsKeyResolver` is created first and is the head of the chain.
2. `BootstrapTlsKeyResolver` is registered and appended via `setNext()`.

**Resulting order:** `HelloTlsKeyResolver` → `BootstrapTlsKeyResolver`.

The `Stack` receives the head resolver by calling `TlsKeyResolverRegistry.getResolvers()` (`Stack.kt:71` in the `create(...)` call).

---

## 2. How does `BootstrapTlsKeyResolver` decide whether to return a key? What identity string does it match?

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolver.kt:17-27`

```kotlin
companion object {
    private val BOOTSTRAP_KEY_ID = "BOOTSTRAP".toByteArray(Charsets.UTF_8)
    // private val BOOTSTRAP_KEY = byteArrayOf(...)  // omitted: secret key bytes
}

override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? {
    return if (keyId.contentEquals(BOOTSTRAP_KEY_ID)) BOOTSTRAP_KEY else null
}
```

**Answer:** It returns the bootstrap key **only** when the requested PSK identity bytes exactly equal the UTF-8 bytes of the string **`"BOOTSTRAP"`**. Otherwise it returns `null`, allowing the chain to fall through (although Bootstrap is currently the last resolver, so the chain simply fails).

---

## 3. Does `TlsKeyResolver.resolveKey` log anything or call anything that could fail silently?

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolver.kt:28-35`

```kotlin
fun resolve(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? {
    val keyIdStr = keyId.toString(Charsets.UTF_8)
    android.util.Log.i("BlueBitGG_Tls", "resolve called keyId='$keyIdStr' nodeKey=${nodeKey.getValue()}")
    val result = resolveKey(nodeKey, keyId)
    android.util.Log.i("BlueBitGG_Tls", "resolve result: ${if (result != null) "FOUND (${result.size} bytes)" else "NOT FOUND"}")
    if (result != null) return result
    return next?.resolve(nodeKey, keyId)
}
```

**Answer:**

- `resolve()` itself **does log** the requested identity string and node-key value, and whether a key was found.
- The abstract `resolveKey()` in subclasses (`HelloTlsKeyResolver`, `BootstrapTlsKeyResolver`) performs only a `contentEquals()` check and returns either the key or `null`.
- There is **no silent failure path**: Kotlin's `ByteArray.toString(Charsets.UTF_8)` replaces invalid UTF-8 bytes rather than throwing, and `contentEquals()` cannot throw for non-null arrays. If a subclass implementation threw, the exception would propagate back to native code and likely crash the process rather than fail silently.

**Caveat:** The base-class log emits the requested identity, so it is important not to log the resulting secret key bytes. The current code only logs key size, not contents.

---

## 4. In `Stack.kt`, what does `setDtlsStatusListener` do? Can it distinguish resolver failure, auth failure, protocol mismatch, and timeout? What information is available?

**Source:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/Stack.kt:105-117`

```kotlin
private var dtlsStatusListener: ((state: Int, code: Int, pskIdentity: ByteArray?) -> Unit)? = null

fun setDtlsStatusListener(listener: ((state: Int, code: Int, pskIdentity: ByteArray?) -> Unit)?) {
    dtlsStatusListener = listener
}

@Suppress("unused")
fun onDtlsStatusChange(state: Int, code: Int, pskIdentity: ByteArray?) {
    val pskStr = pskIdentity?.toString(Charsets.UTF_8) ?: "null"
    val pskHex = pskIdentity?.joinToString("") { "%02X".format(it) } ?: "null"
    Log.i(TAG, "onDtlsStatusChange state=$state code=$code psk='$pskStr' pskHex=$pskHex")
    dtlsStatusListener?.invoke(state, code, pskIdentity)
}
```

**Answer:**

- `setDtlsStatusListener` is a plain setter that stores a Kotlin callback. The callback later receives `state`, `code`, and the `pskIdentity` byte array.
- `onDtlsStatusChange` is invoked from native code. It logs the raw values and forwards them to the stored listener.

**Distinguishability:**

| Condition | Observable via `onDtlsStatusChange`? | Notes |
|-----------|--------------------------------------|-------|
| Resolver failure (unknown PSK identity) | **Partially** | `state == 3` (error) with `code == -10601` maps to `GG_ERROR_TLS_UNKNOWN_IDENTITY` per project reference docs (`reference_transport_api_report.md:330`). |
| Authentication failure / bad hello | **Partially** | `state == 3` with codes such as `-10602` (`GG_ERROR_TLS_BAD_CLIENT_HELLO`) or `-10603` (`GG_ERROR_TLS_BAD_SERVER_HELLO`). |
| Protocol mismatch / unmapped TLS error | **Partially** | `state == 3` with `code == -10604` (`GG_ERROR_TLS_UNMAPPED_LIB_ERROR`). |
| Timeout | **No** | The listener receives no explicit timeout event. `DtlsBootstrapProbe` relies on a Kotlin coroutine `withTimeoutOrNull(20_000ms)` (`DtlsProbeStateMachine.kt:42`) and never sees a state change for a timeout. |

**Available information:**

- `state`: protocol state (`0` init, `1` handshake, `2` session, `3` error, per `reference_transport_api_report.md:317-320` and `DtlsProbeStateMachine.kt:40-41`).
- `code`: native error code (`0` when no error; `-10601`/`-10602`/`-10603`/`-10604` for TLS errors).
- `pskIdentity`: the identity bytes associated with the event (may be `null` or empty).

The Kotlin binding itself does **not** translate codes into typed failure reasons; interpretation depends on matching the numeric code against the native GoldenGate constants.

---

## 5. Does `DtlsBootstrapProbe` clear the listener on cleanup? Could a stale listener cause issues?

**Source:** `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt:439-459`

```kotlin
private fun cleanup(result: ProbeResult): ProbeResult {
    Log.i(TAG, "Cleanup: stage=${result.stage} success=${result.success} tx=${result.txCount} rx=${result.rxCount}")
    try { coapEndpoint?.detach() } catch (_: Exception) { }
    try { coapEndpoint?.close() } catch (_: Exception) { }
    try { stack?.close() } catch (_: Exception) { }
    try { bridge?.close() } catch (_: Exception) { }
    gatt?.disconnect()
    gatt?.close()

    gatt = null
    coapEndpoint = null
    stack = null
    bridge = null
    transmitChar = null
    ephemeralCharUuid = null

    Log.i(TAG, "PROBE_END: ${result.stage}")
    Log.i(TAG, "========================================")
    return result
}
```

**Answer:**

- `cleanup()` **does not** call `stack?.setDtlsStatusListener(null)`.
- The lambda installed in `startStack()` (`DtlsBootstrapProbe.kt:394`) captures `machine` and `dtlsSessionDeferred`. After cleanup those objects are no longer used, but the `Stack` instance still holds the listener reference until it is garbage collected.
- **Potential issue:** If the native layer posts a late `onDtlsStatusChange` after `close()`, the listener could invoke a completed/cancelled `CompletableDeferred` or touch a `DtlsProbeStateMachine` whose lifecycle has logically ended. In practice, `stack?.close()` calls native `destroy(...)`, which should stop further callbacks, but the Kotlin side has no explicit guard. A stale listener is a minor leak/defensive-coding risk.

---

## 6. From the log, how many Gattlink frames were exchanged before the DTLS error? Does this prove the Gattlink transport is ready, or only that bytes flowed?

**Captured log (reformatted):**

```
TX → ABBAFF01: 1 bytes: 80
onDtlsStatusChange state=1 code=0 psk='' pskHex=
TX → ABBAFF01: 5 bytes: 8100000808
Bridge RX data: 130 bytes
TX → ABBAFF01: 160 bytes  (DTLS ClientHello-like)
Bridge RX data: 151 bytes
TX → ABBAFF01: 44 bytes
onDtlsStatusChange state=3 code=-10601 psk='' pskHex=
onDtlsStatusChange state=0 code=0 psk='' pskHex=
```

**Answer:**

- **Outbound (TX) Gattlink frames before the error:** 4 frames (1-byte, 5-byte, 160-byte, 44-byte).
- **Inbound (RX) data deliveries before the error:** 2 data events (130 bytes, 151 bytes), logged by the `Bridge.onRxData` callback.
- The DTLS state transitions from `state=1` (handshake) to `state=3` (error, code `-10601`) **after** the 44-byte TX, so the handshake had already progressed through several message round-trips.

**Does this prove Gattlink is ready?**

- It proves **more than basic byte flow**: notifications are enabled, the MTU is large enough to carry a 160-byte DTLS record, and the peer is responsive enough to complete multiple Gattlink RX/TX exchanges.
- It does **not** prove the Gattlink session is fully established or stable, because the DTLS handshake never reached `state=2` (session). A protocol or identity failure is currently the dominant symptom; transport readiness can only be considered confirmed once DTLS completes successfully.

---

## Recommendation: smallest next diagnostic step

The failure is **`GG_ERROR_TLS_UNKNOWN_IDENTITY` (`state=3`, `code=-10601`)**, which means the DTLS peer requested a PSK identity that the resolver chain could not find.

**Recommended next step:** Capture a full log filtered for both tags and verify the exact identity string being requested when `TlsKeyResolver.resolve()` is invoked.

```
adb logcat -s BlueBitGG_Tls:D BlueBitGG_Stack:D BlueBitDTLSProbe:D
```

Specifically, look for:

```
BlueBitGG_Tls: resolve called keyId='...' nodeKey=...
BlueBitGG_Tls: resolve result: NOT FOUND
```

If the requested `keyId` is **not** `"BOOTSTRAP"` (e.g., it is empty, a different string, or raw bytes that do not decode to UTF-8), then the native DTLS stack or the remote device is using the wrong PSK identity. If the `keyId` is `"BOOTSTRAP"` and the resolver still returns `NOT FOUND`, then the issue is in the resolver chain itself.

No code changes are required for this step; it is purely an additional logging capture.
