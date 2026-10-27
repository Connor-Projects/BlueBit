# GoldenGate native DTLS error `-10601` investigation

## Executive summary

The sequence observed by the BlueBit diagnostic probe:

```
onDtlsStatusChange state=1 code=0 psk=''
onDtlsStatusChange state=3 code=-10601 psk=''
onDtlsStatusChange state=0 code=0 psk=''
```

maps directly to the GoldenGate native `GG_TlsProtocolState` enum:

| state | name | meaning |
|-------|------|---------|
| 0 | `GG_TLS_STATE_INIT` / `TLS_STATE_INIT` | Initial state after creation |
| 1 | `GG_TLS_STATE_HANDSHAKE` / `TLS_STATE_HANDSHAKE` | During the handshake phase |
| 2 | `GG_TLS_STATE_SESSION` / `TLS_STATE_SESSION` | After the handshake has completed |
| 3 | `GG_TLS_STATE_ERROR` / `TLS_STATE_ERROR` | After an error has occurred |

Error code `-10601` is `GG_ERROR_TLS_UNKNOWN_IDENTITY`: the DTLS server (hub/host mode) received a PSK identity from the peer for which **no key could be resolved** by the registered `TlsKeyResolver` chain. Because the native status struct only reports `psk_identity` after a successful handshake, the callback shows an empty PSK identity even though the resolver was invoked and recorded the identity.

After a server-side `TLS_STATE_ERROR`, the native DTLS state machine automatically resets itself back to `GG_TLS_STATE_INIT` and then immediately re-enters `GG_TLS_STATE_HANDSHAKE`, which is the source of the final `state=0` callback in the probe log. Higher-level Kotlin code (e.g. `StackPeer`) deliberately ignores `TLS_STATE_ERROR` because "retries [are] build in XP library."

---

## 1. DTLS status enum values (state 0, 1, 2, 3)

### C definition

Source: `G:/Dev/fitbit-air-notification-bridge/xp/tls/gg_tls.h`

```c
// xp/tls/gg_tls.h  lines 86-91
typedef enum {
    GG_TLS_STATE_INIT,      ///< Initial state after creation
    GG_TLS_STATE_HANDSHAKE, ///< During the handshake phase
    GG_TLS_STATE_SESSION,   ///< After the handshake has completed
    GG_TLS_STATE_ERROR      ///< After an error has occurred
} GG_TlsProtocolState;
```

Because this is an uninitialised C enum, the integer values are:

| enum constant | integer |
|---------------|---------|
| `GG_TLS_STATE_INIT` | `0` |
| `GG_TLS_STATE_HANDSHAKE` | `1` |
| `GG_TLS_STATE_SESSION` | `2` |
| `GG_TLS_STATE_ERROR` | `3` |

### Kotlin mirror

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/DtlsProtocolStatus.kt`

```kotlin
// DtlsProtocolStatus.kt  lines 10-13
enum class TlsProtocolState(val value: Int) {
    TLS_STATE_INIT(0),      ///< Initial state after creation
    TLS_STATE_HANDSHAKE(1), ///< During the handshake phase
    TLS_STATE_SESSION(2),   ///< After the handshake has completed
    TLS_STATE_ERROR(3),      ///< After an error has occurred
    TLS_STATE_UNKNOWN(-1);
```

---

## 2. Exact meaning of error code `-10601`

`-10601` is **`GG_ERROR_TLS_UNKNOWN_IDENTITY`** ("TLS unknown identity").

### Where the value is defined

Source: `G:/Dev/fitbit-air-notification-bridge/xp/common/gg_results.h`

```c
// gg_results.h  line 96
#define GG_ERROR_BASE_TLS              (GG_ERROR_BASE - 600) ///< Base for TLS error codes
```

`GG_ERROR_BASE` is `-10000`, so `GG_ERROR_BASE_TLS == -10600`.

Source: `G:/Dev/fitbit-air-notification-bridge/xp/tls/gg_tls.h`

```c
// gg_tls.h  lines 182-186
#define GG_ERROR_TLS_FATAL_ALERT_MESSAGE           (GG_ERROR_BASE_TLS - 0)
#define GG_ERROR_TLS_UNKNOWN_IDENTITY              (GG_ERROR_BASE_TLS - 1)
#define GG_ERROR_TLS_ILLEGAL_PARAMETER             (GG_ERROR_BASE_TLS - 2)
#define GG_ERROR_TLS_DECODE_ERROR                  (GG_ERROR_BASE_TLS - 3)
#define GG_ERROR_TLS_UNMAPPED_LIB_ERROR            (GG_ERROR_BASE_TLS - 4)
```

Therefore:

```
GG_ERROR_TLS_UNKNOWN_IDENTITY = -10600 - 1 = -10601
```

### Kotlin mapping confirms the same name/code

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/GoldenGateNativeResult.kt`

```kotlin
// GoldenGateNativeResult.kt  lines 66-67
GG_ERROR_TLS_FATAL_ALERT_MESSAGE  ((GG_ERROR_BASE - 600), "TLS FATAL alert message"),
GG_ERROR_TLS_UNKNOWN_IDENTITY     ((GG_ERROR_BASE - 601), "TLS unknown identity"),
```

### How it is produced at runtime

Source: `G:/Dev/fitbit-air-notification-bridge/xp/tls/ports/mbedtls/gg_mbedtls_tls.c`

```c
// gg_mbedtls_tls.c  lines 120-124 (MapErrorCode)
case MBEDTLS_ERR_SSL_UNKNOWN_IDENTITY:
    return GG_ERROR_TLS_UNKNOWN_IDENTITY;
```

In context, this error is returned when the mbedTLS PSK callback fails to resolve the identity supplied by the remote peer:

```c
// gg_mbedtls_tls.c  lines 748-802  (GG_DtlsProtocol_ResolvePsk)
static int
GG_DtlsProtocol_ResolvePsk(void*               _self,
                          mbedtls_ssl_context* ssl_context,
                          const unsigned char* psk_identity,
                          size_t               psk_identity_size)
{
    ...
    GG_Result result = GG_TlsKeyResolver_ResolveKey(self->key_resolver,
                                                    psk_identity,
                                                    psk_identity_size,
                                                    psk,
                                                    &psk_size);
    ...
    if (GG_FAILED(result)) {
        ...
        return MBEDTLS_ERR_SSL_UNKNOWN_IDENTITY;
    }
    ...
}
```

So in this trace, **the Fitbit Inspire 3 sent a PSK identity that the BlueBit probe's resolver chain could not supply a key for**, causing mbedTLS to abort the handshake with `MBEDTLS_ERR_SSL_UNKNOWN_IDENTITY`, which GoldenGate maps to `-10601`.

---

## 3. Where native code calls `onDtlsStatusChange`

The Java/Kotlin callback is invoked from the JNI `StackListener`.

### JNI callback signature

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/GoldenGateBindings/src/main/cpp/stackbuilder/jni_gg_stackbuilder.cpp`

```cpp
// jni_gg_stackbuilder.cpp  line 484
jmethodID dtlsCallback = env->GetMethodID(clazz, "onDtlsStatusChange", "(II[B)V");
```

### Native event-forwarding path

`GG_DtlsProtocol` emits `GG_EVENT_TYPE_TLS_STATE_CHANGE`. The stack builder wraps it in `GG_EVENT_TYPE_STACK_EVENT_FORWARD`. The JNI listener unwraps it, calls `GG_DtlsProtocol_GetStatus`, and forwards `state`, `last_error`, and `psk_identity` to Kotlin.

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/GoldenGateBindings/src/main/cpp/stackbuilder/jni_gg_stackbuilder.cpp`

```cpp
// jni_gg_stackbuilder.cpp  lines 189-207
static void onTlsEvent(EventListener *listener, int tlsState, int tlsLastError,
                       const uint8_t* pskIdentity, int pskIdentitySize) {
    JNIEnv *env = Loop_GetJNIEnv();

    jbyteArray pskIdentityByteArray = env->NewByteArray(pskIdentitySize);
    env->SetByteArrayRegion(pskIdentityByteArray,
                            0,
                            pskIdentitySize,
                            (jbyte *)pskIdentity);

    env->CallVoidMethod(listener->receiver,
                        listener->dtlsCallback,
                        tlsState,
                        tlsLastError,
                        pskIdentityByteArray);

    env->DeleteLocalRef(pskIdentityByteArray);
}
```

```cpp
// jni_gg_stackbuilder.cpp  lines 222-246  (StackListener_OnEvent)
switch (forward_event->forwarded->type) {
    case GG_EVENT_TYPE_TLS_STATE_CHANGE: {
        GG_DtlsProtocolStatus dtls_status;
        GG_DtlsProtocol *dtls_protocol = (GG_DtlsProtocol *) forward_event->forwarded->source;
        GG_DtlsProtocol_GetStatus(dtls_protocol, &dtls_status);
        onTlsEvent(self, dtls_status.state, dtls_status.last_error,
                   dtls_status.psk_identity, (int)dtls_status.psk_identity_size);
        break;
    }
    ...
}
```

### Kotlin receiver

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/stack/Stack.kt`

```kotlin
// Stack.kt  lines 168-171
@Keep
fun onDtlsStatusChange(tlsState: Int, tlsLastError: Int, pskIdentity: ByteArray) {
    Timber.d("DTLS state change received tlsState: $tlsState, tlsLastError: $tlsLastError, pskIdentity: $pskIdentity")
    val dtlsProtocolStatus = DtlsProtocolStatus(tlsState, tlsLastError, String(pskIdentity))
    lastDtlsState = dtlsProtocolStatus.state
    dtlsEventSubject.onNext(dtlsProtocolStatus)
}
```

---

## 4. When is the PSK resolver (`TlsKeyResolver`) invoked from native code?

### Only in hub/server mode

The JNI stack builder creates a `DTLS_SERVER` element only when the descriptor contains `'D'` and `isNode == JNI_FALSE` (i.e. hub/host mode).

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/GoldenGateBindings/src/main/cpp/stackbuilder/jni_gg_stackbuilder.cpp`

```cpp
// jni_gg_stackbuilder.cpp  lines 363-379
if (strchr(args->descriptor, 'D')) {
    if (isNode == JNI_FALSE) { // HUB mode
        KeyResolver *key_resolver = ...;
        ...
        options.serverOptions = {
            .base = { ... },
            .key_resolver = GG_CAST(key_resolver, GG_TlsKeyResolver)
        };
        parameters[parameter_count].element_type = GG_STACK_ELEMENT_TYPE_DTLS_SERVER;
        ...
    } else {
        // NODE mode -> DTLS client with fixed bootstrap PSK
        options.clientOptions = { ... };
        parameters[parameter_count].element_type = GG_STACK_ELEMENT_TYPE_DTLS_CLIENT;
        ...
    }
}
```

### Native resolver delegate

The `KeyResolver` registered with the DTLS server implements `GG_TlsKeyResolver` by calling back into Java/Kotlin:

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/GoldenGateBindings/src/main/cpp/stackbuilder/jni_gg_stackbuilder.cpp`

```cpp
// jni_gg_stackbuilder.cpp  lines 124-166
static GG_Result
TlsKeyResolver_ResolvePsk(
        GG_TlsKeyResolver *_self,
        const uint8_t *key_identity,
        size_t key_identity_size,
        uint8_t *key,
        size_t *key_size)
{
    ...
    // delegate to kotlin to resolve key
    jbyteArray key_byte_array = resolve(
            self->node_key,
            self->tls_key_resolver,
            key_identity,
            key_identity_size);
    ...
}
```

### mbedTLS calls it for each handshake attempt

Source: `G:/Dev/fitbit-air-notification-bridge/xp/tls/ports/mbedtls/gg_mbedtls_tls.c`

```c
// gg_mbedtls_tls.c  lines 748-802
static int
GG_DtlsProtocol_ResolvePsk(void*               _self,
                          mbedtls_ssl_context* ssl_context,
                          const unsigned char* psk_identity,
                          size_t               psk_identity_size)
{
    ...
    GG_Result result = GG_TlsKeyResolver_ResolveKey(self->key_resolver, ...);
    ...
}
```

This callback is registered once at server setup:

```c
// gg_mbedtls_tls.c  line 829
mbedtls_ssl_conf_psk_cb(&self->ssl_config, GG_DtlsProtocol_ResolvePsk, self);
```

**Answer:** `TlsKeyResolver.resolve()` is invoked from native code **every time the mbedTLS server processes a ClientHello PSK identity** during a handshake. If the server auto-retries after an error (see section 6), the resolver can be invoked again on the next handshake attempt.

---

## 5. Why is `pskIdentity` empty in the callback even though a resolver is registered?

The native `GG_DtlsProtocolStatus` struct explicitly says:

Source: `G:/Dev/fitbit-air-notification-bridge/xp/tls/gg_tls.h`

```c
// gg_tls.h  lines 169-173
typedef struct {
    GG_TlsProtocolState state;             ///< Current state of the protocol
    GG_Result           last_error;        ///< Last error, if any (GG_SUCCESS when no error has occurred)
    const uint8_t*      psk_identity;      ///< PSK identity (only valid after a successful handshake)
    size_t              psk_identity_size; ///< Size of the PSK identity
} GG_DtlsProtocolStatus;
```

And `GG_DtlsProtocol_GetStatus` implements that rule:

Source: `G:/Dev/fitbit-air-notification-bridge/xp/tls/ports/mbedtls/gg_mbedtls_tls.c`

```c
// gg_mbedtls_tls.c  lines 1237-1248
void
GG_DtlsProtocol_GetStatus(GG_DtlsProtocol* self, GG_DtlsProtocolStatus* status)
{
    *status = (GG_DtlsProtocolStatus) {
        .state      = self->state,
        .last_error = self->last_error
    };

    // the PSK identity depends on the state and the role
    if (status->state == GG_TLS_STATE_SESSION) {
        status->psk_identity_size = GG_DynamicBuffer_GetDataSize(self->psk_identity);
        status->psk_identity      = status->psk_identity_size ? GG_DynamicBuffer_GetData(self->psk_identity) : NULL;
    }
}
```

For `GG_TLS_STATE_ERROR` (state 3) and `GG_TLS_STATE_INIT` (state 0), `psk_identity` is left `NULL` and `psk_identity_size` is `0`. The JNI layer converts that to a zero-length `byte[]`, and Kotlin converts it to `""`.

The reference app works around this by recording the last unresolved identity in a diagnostic resolver:

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/DiagnosticTlsIdentityRecorder.kt`

```kotlin
// DiagnosticTlsIdentityRecorder.kt  lines 14-19
object DiagnosticTlsIdentityRecorder : TlsKeyResolver() {
    private val lastIdentity = AtomicReference<ByteArray?>()

    override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? {
        lastIdentity.set(keyId.copyOf())
        return null
    }
```

And rendering it only when `lastError == GG_ERROR_TLS_UNKNOWN_IDENTITY`:

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/AbstractHostActivity.kt`

```kotlin
// AbstractHostActivity.kt  lines 392-395
val requestedIdentity = if (it.lastError == GG_ERROR_TLS_UNKNOWN_IDENTITY) {
    DiagnosticTlsIdentityRecorder.renderedIdentity()
} else {
    null
}
```

**Answer:** `pskIdentity` is empty because the native status API intentionally **only exposes the PSK identity after the handshake has succeeded** (`GG_TLS_STATE_SESSION`). In `TLS_STATE_ERROR` or `TLS_STATE_INIT` the identity field is `NULL`/empty even though the resolver was called and knows what identity was requested.

---

## 6. Native retry behavior after state 3 / `-10601`

### Server mode auto-reset

In `GG_DtlsProtocol_AdvanceHandshake`, after an error the state machine detects server role and resets itself:

Source: `G:/Dev/fitbit-air-notification-bridge/xp/tls/ports/mbedtls/gg_mbedtls_tls.c`

```c
// gg_mbedtls_tls.c  lines 1183-1193
// after an error in server mode, go back to init and re-handshake
if (self->role == GG_TLS_ROLE_SERVER && self->state == GG_TLS_STATE_ERROR) {
    GG_LOG_FINE("resetting session");
    GG_Result result = GG_DtlsProtocol_Reset(self);
    if (GG_SUCCEEDED(result)) {
        self->state = GG_TLS_STATE_HANDSHAKE;
        GG_DtlsProtocol_EmitStateChangeEvent(self);
    }
}
```

`GG_DtlsProtocol_Reset` clears the error and sets state to `GG_TLS_STATE_INIT`, emitting a state-change event:

Source: `G:/Dev/fitbit-air-notification-bridge/xp/tls/ports/mbedtls/gg_mbedtls_tls.c`

```c
// gg_mbedtls_tls.c  lines 1217-1234
GG_Result
GG_DtlsProtocol_Reset(GG_DtlsProtocol* self)
{
    if (self->state == GG_TLS_STATE_INIT) {
        GG_LOG_FINE("ignoring reset, we're already in the INIT state");
        return GG_SUCCESS;
    }

    int ssl_result = mbedtls_ssl_session_reset(&self->ssl_context);
    if (ssl_result != 0) {
        ...
    } else {
        self->last_error = GG_SUCCESS;
        self->state = GG_TLS_STATE_INIT;
        GG_DtlsProtocol_EmitStateChangeEvent(self);
        return GG_SUCCESS;
    }
}
```

So the observed sequence:

```
state=1 code=0      -> GG_TLS_STATE_HANDSHAKE
state=3 code=-10601 -> GG_TLS_STATE_ERROR
state=0 code=0      -> GG_TLS_STATE_INIT (emitted by the auto-reset)
```

is exactly what the server-side DTLS state machine emits. After the reset it would normally transition back to `GG_TLS_STATE_HANDSHAKE` (state 1) almost immediately, but the probe log may have captured only the `INIT` event before further events occurred, or the subsequent handshake events may have been dropped/merged at the logging layer.

### Stack-level restart/reset triggers

The stack builder also restarts DTLS on Gattlink session ready events and resets it on Gattlink session reset events:

Source: `G:/Dev/fitbit-air-notification-bridge/xp/stack_builder/gg_stack_builder.c`

```c
// gg_stack_builder.c  lines 785-810
case GG_EVENT_TYPE_GATTLINK_SESSION_READY:
    forward_event = true;
    if (self->dtls_element) {
        GG_DtlsProtocolStatus dtls_status;
        GG_StackDtlsElement_GetStatus(self->dtls_element, &dtls_status);
        if (dtls_status.state == GG_TLS_STATE_INIT) {
            GG_LOG_INFO("restarting DTLS");
            GG_StackDtlsElement_Start(self->dtls_element);
        }
    }
    break;

case GG_EVENT_TYPE_GATTLINK_SESSION_RESET:
    forward_event = true;
    if (self->dtls_element) {
        GG_DtlsProtocolStatus dtls_status;
        GG_StackDtlsElement_GetStatus(self->dtls_element, &dtls_status);
        if (dtls_status.state != GG_TLS_STATE_INIT) {
            GG_LOG_INFO("resetting DTLS");
            GG_StackDtlsElement_Reset(self->dtls_element);
        }
    }
    break;
```

### Kotlin layer ignores `TLS_STATE_ERROR` because retries are built in

Source: `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/stack/StackPeer.kt`

```kotlin
// StackPeer.kt  lines 293-298
/*
 * Only react to TLS_STATE_SESSION, exclude all other states as they
 * don't matter when emitting a true connection and there are
 * retries build in XP library for TLS_STATE_ERROR state
 */
tlsStatus.state == TLS_STATE_SESSION
```

**Answer:** After a server-side `TLS_STATE_ERROR`, the native DTLS protocol **automatically resets** back to `GG_TLS_STATE_INIT` and then immediately re-enters `GG_TLS_STATE_HANDSHAKE`. The final `state=0` callback in the log is the reset event. The stack builder can also restart/reset DTLS on Gattlink session ready/reset events. Client mode does **not** auto-reset; it stays in `TLS_STATE_ERROR`.

---

## Source index

| Claim | Primary source | Line(s) |
|-------|----------------|---------|
| DTLS state enum (C) | `xp/tls/gg_tls.h` | 86-91 |
| DTLS state enum (Kotlin) | `GoldenGateBindings/.../dtls/DtlsProtocolStatus.kt` | 10-13 |
| `GG_ERROR_BASE_TLS` | `xp/common/gg_results.h` | 96 |
| TLS error code macros | `xp/tls/gg_tls.h` | 182-186 |
| Kotlin error enum | `GoldenGateBindings/.../GoldenGateNativeResult.kt` | 66-67 |
| mbedTLS error → `GG_ERROR_TLS_UNKNOWN_IDENTITY` | `xp/tls/ports/mbedtls/gg_mbedtls_tls.c` | 120-124 |
| PSK resolver callback in mbedTLS | `xp/tls/ports/mbedtls/gg_mbedtls_tls.c` | 748-802 |
| `GG_DtlsProtocol_GetStatus` | `xp/tls/ports/mbedtls/gg_mbedtls_tls.c` | 1237-1248 |
| Server auto-reset after error | `xp/tls/ports/mbedtls/gg_mbedtls_tls.c` | 1183-1193 |
| `GG_DtlsProtocol_Reset` | `xp/tls/ports/mbedtls/gg_mbedtls_tls.c` | 1217-1234 |
| DTLS element create/start/reset | `xp/stack_builder/gg_stack_builder.c` | 495-567, 785-810 |
| JNI `onDtlsStatusChange` signature | `GoldenGateBindings/.../stackbuilder/jni_gg_stackbuilder.cpp` | 484 |
| JNI `onTlsEvent` / `StackListener_OnEvent` | `GoldenGateBindings/.../stackbuilder/jni_gg_stackbuilder.cpp` | 189-246 |
| JNI resolver bridge | `GoldenGateBindings/.../stackbuilder/jni_gg_stackbuilder.cpp` | 124-166 |
| Server vs client DTLS element setup | `GoldenGateBindings/.../stackbuilder/jni_gg_stackbuilder.cpp` | 363-379 |
| Kotlin `Stack.onDtlsStatusChange` | `GoldenGateBindings/.../stack/Stack.kt` | 168-171 |
| Kotlin retry policy in `StackPeer` | `GoldenGateConnectionManager/.../node/stack/StackPeer.kt` | 293-298 |
| Diagnostic identity recorder | `app/.../DiagnosticTlsIdentityRecorder.kt` | 14-19 |
| Reference app error rendering | `app/.../AbstractHostActivity.kt` | 392-395, 430-452 |

---

## Conclusion

- **`-10601` = `GG_ERROR_TLS_UNKNOWN_IDENTITY`**: the remote peer presented a PSK identity that the hub-side resolver chain could not resolve to a key.
- The trace `state=3 code=-10601` is therefore a **server-side handshake failure**, not a transport failure.
- The empty `psk`/`pskIdentity` in the callback is expected: GoldenGate only reports the PSK identity in `TLS_STATE_SESSION` (state 2). To see the identity that caused the failure, the app must record it inside the resolver itself (as the reference `DiagnosticTlsIdentityRecorder` does).
- The subsequent `state=0` callback is the native **server-mode auto-reset** (`GG_DtlsProtocol_Reset`) after the handshake error. The native stack will then attempt the handshake again, so `TlsKeyResolver` may be invoked repeatedly.

No files were modified; no CoAP requests were sent; no per-device secrets were extracted.
