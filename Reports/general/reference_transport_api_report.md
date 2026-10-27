# Golden Gate Transport API Reference Report

**Source project:** `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate`  
**APK:** `G:/Dev/fitbit-air-notification-bridge/platform/android/goldengate/app/build/outputs/apk/debug/app-debug.apk` (source used as ground truth)  
**Date inspected:** 2026-10-10  
**Scope:** Phase 2 – reusable Golden Gate transport components for a DTLS bootstrap experiment.

---

## 1. Exact Public API Signatures

### 1.1 `StackPeer<T: StackService>`
**File:** `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/stack/StackPeer.kt`

```kotlin
package com.fitbit.goldengate.node.stack

class StackPeer<T: StackService> internal constructor(
    val key: BluetoothAddressNodeKey,
    peerRole: PeerRole,
    val stackConfig: StackConfig,
    stackService: T,
    private val linkupHandler: Linkup = LinkupHandlerProvider.getHandler(peerRole),
    private val peerConnector: PeerConnector = PeerConnector(key.value),
    private val connectionStatusProvider: (GattConnection) -> Observable<PeripheralConnectionStatus> = { GattClientConnectionChangeListener().register(it) },
    private val dtlsEventObservableProvider: (stack: Stack) -> Observable<DtlsProtocolStatus> = { it.dtlsEventObservable },
    private val peerProvider: (gattConnection: GattConnection) -> BitGattPeer = { gattConnection -> BitGattPeer(gattConnection) },
    private val mtuChangeRequesterProvider: (Stack) -> MtuChangeRequester = { stack -> MtuChangeRequester(key.value, stack) },
    private val startMtu: Int = STACK_NODE_DEFAULT_MTU,          // 185
    private val connectTimeout: Long = STACK_NODE_CONNECTION_TIMEOUT_SECONDS, // 60
    private val buildStack: (Bridge) -> Stack = { ... },
    private val buildBridge: (GattConnection) -> Bridge = { Bridge(it) },
    private val mtuUpdateListenerProvider: (GattConnection) -> Observable<Int> = { MtuUpdateListener().register(it) },
    private val stackEventHandlerProvider: (Observable<StackEvent>, GattConnection) -> StackEventHandler = { obs, conn -> StackEventHandler(obs, conn) },
    private val stackEventObservableProvider: (Stack) -> Observable<StackEvent> = { it.stackEventObservable },
    private val fitbitGatt: FitbitGatt = FitbitGatt.getInstance(),
    private val peripheralDisconnector: PeripheralDisconnector = PeripheralDisconnector(fitbitGatt),
    private val linkControllerProvider: (GattConnection) -> LinkController = { LinkControllerProvider.INSTANCE.getLinkController(it) }
): Peer<T>(stackService, peerRole)
```

**Public methods (inherited / usable):**
- `override fun connection(): Observable<PeerConnectionStatus>`
- `override fun disconnect(notifyPeerToDisconnect: Boolean = false)`
- `fun close()` from `Peer<T>` – disconnects and closes `StackService`

**Utility constructor** (used by host app for status callbacks):
```kotlin
constructor(
    key: BluetoothAddressNodeKey,
    peerRole: PeerRole,
    stackConfig: StackConfig,
    stackService: T,
    connectionStatusProvider: (GattConnection) -> Observable<PeripheralConnectionStatus>,
    dtlsEventObservableProvider: (stack: Stack) -> Observable<DtlsProtocolStatus>
): this(...)
```

### 1.2 `DtlsSocketNetifGattlink`
**File:** `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/stack/StackConfig.kt`

```kotlin
package com.fitbit.goldengate.bindings.stack

data class DtlsSocketNetifGattlink(
    override val localAddress: Inet4Address = defaultAddress(),   // 0.0.0.0
    override val localPort: Int = 0,
    override val remoteAddress: Inet4Address = defaultAddress(),  // 0.0.0.0
    override val remotePort: Int = 0
) : StackConfig("DSNG", localAddress, localPort, remoteAddress, remotePort)
```

- Config descriptor passed to JNI: `"DSNG"`
- Sealed base class `StackConfig` exposes `configDescriptor: String`, `localAddress`, `localPort`, `remoteAddress`, `remotePort`.
- `fun StackConfig.isLwipBased() = this !is GattlinkStackConfig`

### 1.3 `CoapEndpointBuilder`
**File:** `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpointBuilder.kt`

```kotlin
package com.fitbit.goldengate.bindings.coap

object CoapEndpointBuilder: () -> CoapEndpoint {
    override fun invoke(): CoapEndpoint = CoapEndpoint()
}
```

### 1.4 `TlsKeyResolverRegistry`
**File:** `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolverRegistry.kt`

```kotlin
package com.fitbit.goldengate.bindings.dtls

object TlsKeyResolverRegistry {
    internal val resolvers: TlsKeyResolver      // chain head
    fun register(tlsKeyResolver: TlsKeyResolver)
}
```

- Pre-registers `HelloTlsKeyResolver` ("hello") and `BootstrapTlsKeyResolver` ("BOOTSTRAP").
- `resolvers` is `internal`; external code uses `register()` to add resolvers.

### 1.5 `BootstrapTlsKeyResolver`
**File:** `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolver.kt`

```kotlin
package com.fitbit.goldengate.bindings.dtls

@Keep
internal class BootstrapTlsKeyResolver : TlsKeyResolver() {
    override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray?
}
```

- Class is `internal`; instances are created automatically inside `TlsKeyResolverRegistry`.

### 1.6 `GoldenGateConnectionManagerModule`
**File:** `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/GoldenGateConnectionManagerModule.kt`

```kotlin
package com.fitbit.goldengate

object GoldenGateConnectionManagerModule {
    @JvmStatic
    fun init(applicationContext: Context, loggingEnabled: Boolean = true, isBleCentralRole: Boolean = true)
}
```

- Internally calls `GoldenGate.init(loggingEnabled)` and `GlobalBluetoothGattInitializer.start(context, isBleCentralRole)`.

### 1.7 `PeerRole`
**File:** `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/PeerRole.kt`

```kotlin
package com.fitbit.goldengate.bt

sealed class PeerRole(val name: String) {
    object Central: PeerRole("Central")
    object Peripheral: PeerRole("Peripheral")
}
```

### 1.8 `BluetoothAddressNodeKey`
**File:** `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/node/BluetoothAddressNodeKey.kt`

```kotlin
package com.fitbit.goldengate.bindings.node

data class BluetoothAddressNodeKey(
    override val value: String
) : NodeKey<String> {
    init { verifyBluetoothAddress() }
}
```

- Validates the address with Android’s `BluetoothAdapter.checkBluetoothAddress()`.
- Throws `IllegalArgumentException` on invalid address.

---

## 2. `jni_gg_stackbuilder.cpp` Summary

**File:** `GoldenGateBindings/src/main/cpp/stackbuilder/jni_gg_stackbuilder.cpp`

This JNI bridge builds a native Golden Gate stack according to a one-letter descriptor string and returns a `StackCreationResult` containing a native stack pointer.

### Native stack elements built
1. **Gattlink** – always added (`GG_STACK_ELEMENT_TYPE_GATTLINK`).
   - `initial_max_fragment_size = GG_STACK_BUILDER_DEFAULT_GATTLINK_FRAGMENT_SIZE`
   - Probe config defaults: window 1000 ms, 50 samples, threshold 400 byte·seconds.
2. **DTLS** – added only if descriptor contains `'D'`.
   - **Hub (peripheral / `isNode == false`):** `GG_STACK_ELEMENT_TYPE_DTLS_SERVER` with a `TlsKeyResolver` delegating to the Kotlin chain (`TlsKeyResolverRegistry.resolvers`).
   - **Node (central / `isNode == true`):** `GG_STACK_ELEMENT_TYPE_DTLS_CLIENT` with the hard-coded bootstrap PSK identity/key.
3. **Datagram socket** – added only if `localPort != 0 || remotePort != 0` (`GG_STACK_ELEMENT_TYPE_DATAGRAM_SOCKET`).

### Descriptor → stack mapping
| Descriptor | Stack |
|------------|-------|
| `"G"` | Gattlink only |
| `"SNG"` | Socket + Netif + Gattlink |
| `"DSNG"` | DTLS + Socket + Netif + Gattlink |

### DTLS cipher suites supported
- `GG_TLS_PSK_WITH_AES_128_CCM`
- `GG_TLS_PSK_WITH_AES_128_GCM_SHA256`
- `GG_TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256`

### Key JNI callbacks
- `Stack.onDtlsStatusChange(tlsState: Int, tlsLastError: Int, pskIdentity: ByteArray)`
- `Stack.onStackEvent(eventId: Int, data: Int)`

---

## 3. Gradle Dependencies / Module Coordinates

### Modules (local)
| Module | Gradle path | Package | Depends on |
|--------|-------------|---------|------------|
| `GoldenGateBindings` | `:GoldenGateBindings` | `com.fitbit.goldengate.bindings` | Kotlin stdlib, RxJava2, OkHttp, Timber, AndroidX annotations |
| `GoldenGateConnectionManager` | `:GoldenGateConnectionManager` | `com.fitbit.goldengate.connectionmanager` | `:RxFitbitGatt`, `:GoldenGateBindings`, `:linkcontroller`, BitGatt |
| `RxFitbitGatt` | `:RxFitbitGatt` | `com.fitbit.bluetooth.fbgatt.rx` | BitGatt, Kotlin stdlib, RxJava2, Timber |
| `linkcontroller` | `:linkcontroller` | `com.fitbit.linkcontroller` | BitGatt |
| `app` | `:app` | `io.github.paceaitian.fitbitairbridge` | All above modules + BitGatt |

### External coordinates (from `gradle.properties`)
```properties
KOTLIN_VERSION = 1.4.31
ANDROIDX_APPCOMPAT_VERSION = 1.2.0
BITGATT_COMMIT_VERSION = v0.9.11
TIMBER_VERSION = 4.7.1
RXJAVA_VERSION = 2.2.12
RXANDROID_VERSION = 2.1.1
OK_HTTP=4.7.2
ANDROID_MATERIAL_VERSION = 1.3.0
ANDROIDX_CONSTRAINTLAYOUT_VERSION = 2.0.4
ANDROIDX_LEGACY_SUPPORT_V4_VERSION = 1.0.0
ANDROIDX_RECYCLEVIEW_VERSION = 1.2.0
```

### BitGatt coordinate
```groovy
implementation "com.github.fitbit:bitgatt:$BITGATT_COMMIT_VERSION"  // v0.9.11
```

### Build toolchain
```properties
BUILD_TOOLS_VERSION = 30.0.2
COMPILE_SDK_VERSION = 30
TARGET_SDK_VERSION = 28
MIN_SDK_VERSION = 21
NDK_VERSION = 23.0.7599858
CMAKE_VERSION = 3.10.2.4988404
Android Gradle Plugin = 4.1.0
```

---

## 4. Gattlink Service / Characteristic UUIDs

### 4.1 Original Gattlink service
**File:** `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/gatt/server/services/gattlink/GattlinkService.kt`

| Item | UUID | Properties |
|------|------|------------|
| Service | `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` | Primary |
| Receive characteristic (Rx) | `ABBAFF01-E56A-484C-B832-8B17CF6CBFE8` | `WRITE_NO_RESPONSE` |
| Transmit characteristic (Tx) | `ABBAFF02-E56A-484C-B832-8B17CF6CBFE8` | `READ` + `NOTIFY` |

### 4.2 Fitbit-registered public Gattlink service
**File:** `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/gatt/server/services/gattlink/FitbitGattlinkService.kt`

| Item | UUID | Properties |
|------|------|------------|
| Service | `0000FD62-0000-1000-8000-00805F9B34FB` | Primary |
| Receive characteristic | `ABBAFF01-E56A-484C-B832-8B17CF6CBFE8` | `WRITE_NO_RESPONSE` |
| Transmit characteristic | `ABBAFF02-E56A-484C-B832-8B17CF6CBFE8` | `READ` + `NOTIFY` |

### 4.3 How the reference app reads/writes them

**When the phone is Central (connects to a tracker that hosts Gattlink):**
- **Write to tracker Rx:** `RemoteGattlinkNodeDataSender` uses `GattCharacteristicWriter.write(serviceUuid, ReceiveCharacteristic.uuid, data)`.
- **Read/notify from tracker Tx:** `RemoteGattlinkNodeDataReceiver` registers `GattClientCharacteristicChangeListener` on `TransmitCharacteristic.uuid`.

**Files:**
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/RemoteGattlinkNodeDataSender.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/RemoteGattlinkNodeDataReceiver.kt`

**When the phone is Peripheral (hosts Gattlink locally):**
- Local `FitbitGattlinkService` is added to the GATT server.
- Incoming writes on `ReceiveCharacteristic` are handled by `ReceiveCharacteristicDataListener`.
- Notifications on `TransmitCharacteristic` are sent via `GattCharacteristicNotifier.notify(serviceId, TransmitCharacteristic.uuid, data)` after CCCD subscription (`TransmitCharacteristicSubscriptionListener`).

---

## 5. Connection Completion Callbacks and DTLS Error Callbacks

### 5.1 Connection completion
`StackPeer.connect()` returns an `Observable<PeerConnectionStatus>` that emits `PeerConnectionStatus.CONNECTED` after:
1. BLE connect
2. Stack + Bridge setup
3. MTU request/update
4. Link-up (service discovery + subscription)
5. Stack start
6. For `DtlsSocketNetifGattlink`: **both** Gattlink connected **and** DTLS `TLS_STATE_SESSION`.

**Key code in `StackPeer.kt`:**
```kotlin
private fun waitForConnectedState(connectionState: ConnectionState): Observable<PeerConnectionStatus> {
    val connectionStatusObservable = getConnectionStatusObservable(connectionState)
    return if (stackConfig is DtlsSocketNetifGattlink) {
        Observable.zip(
            connectionStatusObservable,
            getDtlsEventObservable(connectionState),
            BiFunction { _, _ -> PeerConnectionStatus.CONNECTED }
        )
    } else {
        connectionStatusObservable
    }.timeout(
        Observable.empty<PeerConnectionStatus>().delay(connectTimeout, TimeUnit.SECONDS),
        Function { _: PeerConnectionStatus -> Observable.never<Any>() }
    )
}
```

### 5.2 DTLS state callback
`Stack.onDtlsStatusChange` receives raw state, last error, and PSK identity.

```kotlin
@Keep
fun onDtlsStatusChange(tlsState: Int, tlsLastError: Int, pskIdentity: ByteArray)
```

Mapped to:
```kotlin
class DtlsProtocolStatus(state: Int, val lastError: Int, val pskIdentity: String) {
    enum class TlsProtocolState(val value: Int) {
        TLS_STATE_INIT(0),
        TLS_STATE_HANDSHAKE(1),
        TLS_STATE_SESSION(2),
        TLS_STATE_ERROR(3),
        TLS_STATE_UNKNOWN(-1)
    }
}
```

### 5.3 DTLS error constants used by the host app
From `AbstractHostActivity.kt`:
```kotlin
private const val GG_FAILURE = -1
private const val GG_ERROR_TLS_UNKNOWN_IDENTITY = -10601
private const val GG_ERROR_TLS_BAD_CLIENT_HELLO = -10602
private const val GG_ERROR_TLS_BAD_SERVER_HELLO = -10603
private const val GG_ERROR_TLS_UNMAPPED_LIB_ERROR = -10604
```

`StackPeer.getDtlsEventObservable()` filters for `TLS_STATE_SESSION` and ignores transient `TLS_STATE_ERROR` (native layer performs retries). `lastError` is surfaced in the host UI for diagnostic purposes.

### 5.4 Stack event callback
```kotlin
@Keep
fun onStackEvent(eventId: Int, data: Int)
```
- Forwards events such as `GG_EVENT_TYPE_GATTLINK_SESSION_STALLED` (with stalled time in `data`).

---

## 6. Session Timeout and Cleanup Code

### 6.1 Timeouts
| Timeout | Value | Location |
|---------|-------|----------|
| StackPeer connect timeout | `60 s` | `StackPeer.kt` (`STACK_NODE_CONNECTION_TIMEOUT_SECONDS`) |
| Link-up timeout | `60 s` | `LinkupWithPeerNodeHandler.kt` / `LinkupWithPeerHubHandler.kt` (`LINK_UP_TIMEOUT_SECONDS`) |
| GATT service refresh delay | `2 s` | `LinkupWithPeerNodeHandler.kt` (`GATT_SERVICE_REFRESH_DELAY_IN_SEC`) |
| Air one-shot connect timeout | `60 s` | `AirBridgeOneShotService.kt` (`CONNECT_TIMEOUT_SECONDS`) |

### 6.2 Cleanup paths
**`StackPeer.disconnect()`:**
```kotlin
override fun disconnect(notifyPeerToDisconnect: Boolean) {
    if (notifyPeerToDisconnect) requestTrackerDisconnect(key.value).toObservable().publish().connect()
    disposable?.dispose()
    disconnectPeripheral()
}
```

**`StackPeer.tearDown()` (called on observable dispose):**
- Unregisters link-configuration listener
- Handles disconnection via `LinkControllerProvider`
- Suspends bridge, detaches stack service, resets CoAP filter group
- `stack.close()` and `bridge.close()`

**`Stack.close()` (Kotlin):**
```kotlin
override fun close() {
    if (stackClosed.getAndSet(true)) return
    destroy(thisPointer)
}
```

**Native cleanup (`jni_gg_stackbuilder.cpp::Java_com_fitbit_goldengate_bindings_stack_Stack_destroy`):**
- Detaches transport source sink (`GG_DataSource_SetDataSink(..., NULL)`)
- Removes event listener
- `GG_Stack_Destroy(stackWrapper->stack)`
- Deletes JNI global refs for event listener and key resolver

**`Peer.close()`:**
```kotlin
@Synchronized
fun close() {
    disconnect()
    stackService.close()
}
```

**`NodeMapper.removeNode()`:**
```kotlin
@Synchronized
fun <K: NodeKey<*>> removeNode(nodeKey: K) {
    nodeMap[nodeKey]?.let {
        it.close()
        nodeMap.remove(nodeKey)
    }
}
```

---

## 7. BitGatt and Other Required Dependencies

### 7.1 BitGatt (required)
- Coordinate: `com.github.fitbit:bitgatt:v0.9.11` (via JitPack)
- Core classes used:
  - `com.fitbit.bluetooth.fbgatt.FitbitGatt`
  - `com.fitbit.bluetooth.fbgatt.GattConnection`
  - `com.fitbit.bluetooth.fbgatt.GattServerConnection`
  - `com.fitbit.bluetooth.fbgatt.rx.client.BitGattPeer`
  - `com.fitbit.bluetooth.fbgatt.rx.client.GattCharacteristicWriter`
  - `com.fitbit.bluetooth.fbgatt.rx.client.listeners.GattClientCharacteristicChangeListener`
  - `com.fitbit.bluetooth.fbgatt.rx.client.PeerGattServiceSubscriber`
  - `com.fitbit.bluetooth.fbgatt.rx.PeripheralConnectionStatus`
  - `com.fitbit.bluetooth.fbgatt.rx.PeripheralConnectionChangeListener`
  - `com.fitbit.bluetooth.fbgatt.rx.CentralConnectionChangeListener`
  - `com.fitbit.bluetooth.fbgatt.rx.server.BitGattServer`
  - `com.fitbit.bluetooth.fbgatt.rx.server.listeners.GattServerMtuChangeListener`

### 7.2 RxJava2 (required)
- `io.reactivex.rxjava2:rxjava:2.2.12`
- `io.reactivex.rxjava2:rxandroid:2.1.1`
- Heavily used for connection, link-up, MTU, and DTLS event streams.

### 7.3 Other dependencies
- **Timber** `4.7.1` – logging
- **OkHttp** `4.7.2` – used by CoAP/remote API paths
- **Kotlin stdlib** `1.4.31`
- **AndroidX AppCompat / Annotation** `1.2.0`
- **Material / RecyclerView / ConstraintLayout** – host app UI only

### 7.4 Native dependencies
- NDK `23.0.7599858`
- CMake `3.10.2.4988404`
- Prebuilt `libgg.so` from `xp/build/cmake/android/` (Golden Gate C library)
- ABI filter in reference build: `arm64-v8a` only (debug)

---

## 8. Bootstrap PSK Identity and Key Bytes

**Source:** `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolver.kt` and `jni_gg_stackbuilder.cpp`

```kotlin
internal val BOOTSTRAP_KEY_ID = "BOOTSTRAP".toByteArray()
internal val BOOTSTRAP_KEY = byteArrayOf(
    0x81.toByte(), 0x06, 0x54, 0xE3.toByte(),
    0x36, 0xAD.toByte(), 0xCA.toByte(), 0xB0.toByte(),
    0xA0.toByte(), 0x3C, 0x60, 0xF7.toByte(),
    0x4A, 0xA0.toByte(), 0xB6.toByte(), 0xFB.toByte()
)
```

In the C bridge:
```cpp
static uint8_t BOOTSTRAP_KEY[16] = {
    0x81, 0x06, 0x54, 0xe3, 0x36, 0xad, 0xca, 0xb0,
    0xa0, 0x3c, 0x60, 0xf7, 0x4a, 0xa0, 0xb6, 0xfb
};
static uint8_t BOOTSTRAP_KEY_IDENTITY[9] = {
    'B','O','O','T','S','T','R','A','P'
};
```

**Role mapping:**
- **Node (Central / `isNode = true`)** sends the bootstrap identity/key as the DTLS client.
- **Hub (Peripheral / `isNode = false`)** resolves keys via `TlsKeyResolverRegistry.resolvers`, which first tries `HelloTlsKeyResolver` ("hello") then `BootstrapTlsKeyResolver` ("BOOTSTRAP"), then any user-registered resolver.

---

## 9. Connection Setup Code Snippet

From `AirBridgeOneShotService.kt` (simplified, central connects to a peripheral/tracker):

```kotlin
// 1. Initialize runtime once
AirBridgeRuntime.ensureInitialized(context)

// 2. Create a DTLS+Socket+Netif+Gattlink config
val stackConfig = DtlsSocketNetifGattlink(
    localAddress  = Inet4Address.getByName(DEFAULT_SERVER_ADDRESS) as Inet4Address,
    localPort     = 0,
    remoteAddress = Inet4Address.getByName(DEFAULT_CLIENT_ADDRESS) as Inet4Address,
    remotePort    = 0
)

// 3. Build a StackPeer with a CoAP endpoint
val peer = StackPeer(
    BluetoothAddressNodeKey(address),
    PeerRole.Peripheral,          // phone is the BLE central but GG "hub" (isNode=false)
    stackConfig,
    CoapEndpointBuilder(),
    { connection -> PeripheralConnectionChangeListener().register(connection) },
    { stack -> stack.dtlsEventObservable }
)

// 4. Connect and wait for completion
peer.connection()
    .firstOrError()
    .timeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .map { peer.stackService as CoapEndpoint }
    .subscribe(
        { endpoint -> /* DTLS session established; use CoAP */ },
        { error -> /* handle connection/DTLS error */ }
    )
```

Equivalent builder usage from `CoapNodeProvider.kt`:
```kotlin
private val stackPeerBuilder: StackPeerBuilder<CoapEndpoint> =
    StackPeerBuilder(
        CoapEndpoint::class.java,
        PeerRole.Peripheral,
        CoapEndpointBuilder,
        DtlsSocketNetifGattlink()
    )
```

And from `AbstractHostActivity.kt` using `NodeMapper`:
```kotlin
val stackNode = NodeMapper.instance.get(
    nodeKey,
    getPeerBuilder(PeerRole.Peripheral, stackConfig, listenToGattlinkStatus, listenToDtlsEvents(stackConfig)),
    true
) as Peer<T>
```

---

## 10. Callback / Error Handling Summary

| What | Where | Behavior |
|------|-------|----------|
| Gattlink connected | `PeripheralConnectionChangeListener` / `CentralConnectionChangeListener` | Emits `PeripheralConnectionStatus.CONNECTED` |
| Gattlink disconnected | Same listener | Throws `NodeDisconnectedException` |
| DTLS session ready | `Stack.dtlsEventObservable` | Emits `DtlsProtocolStatus(state=TLS_STATE_SESSION, ...)` |
| DTLS error | `Stack.dtlsEventObservable` | `state=TLS_STATE_ERROR`, `lastError` set; host app can log; native retry exists |
| DTLS unknown identity | `lastError == GG_ERROR_TLS_UNKNOWN_IDENTITY (-10601)` | Host app records requested identity via `DiagnosticTlsIdentityRecorder` |
| Connect timeout | `StackPeer.waitForConnectedState` | 60 s; emits `TimeoutException` |
| Link-up timeout | `LinkupWithPeerNodeHandler.link` / `LinkupWithPeerHubHandler.link` | 60 s |
| Stack event (stall) | `Stack.stackEventObservable` | `GG_EVENT_TYPE_GATTLINK_SESSION_STALLED` with stalled time |
| Cleanup on disconnect | `StackPeer.tearDown`, `Stack.close`, `Bridge.close` | Detach service, destroy native stack, delete JNI refs |

---

## 11. Integration Risks / Dependency Conflicts to Watch For

1. **BitGatt version lock-in** – Reference uses `com.github.fitbit:bitgatt:v0.9.11`. Newer BitGatt versions may have breaking API changes in `FitbitGatt`, `GattConnection`, or listeners.

2. **RxJava2 vs RxJava3** – All Golden Gate modules use `rxjava2`. Mixing with RxJava3 will require bridging or rewriting streams.

3. **NDK / CMake versions** – Reference pins `NDK 23.0.7599858` and `CMake 3.10.2.4988404`. Building the native `libgg.so` with different toolchains may produce ABI or linking issues.

4. **ABI coverage** – Debug APK only builds `arm64-v8a`. If targeting other ABIs, the `GoldenGateBindings/build.gradle` and `xp/build/cmake/android/` outputs must include them.

5. **Kotlin version** – Reference uses Kotlin `1.4.31`. Higher Kotlin versions should generally work, but inline classes and metadata changes could cause incompatibilities.

6. **Android Gradle Plugin 4.1.0 / compileSdk 30** – Older AGP may not support newer Gradle versions; newer AGP may require `namespace`, updated packaging options, etc.

7. **BitGatt singleton lifecycle** – `GoldenGateConnectionManagerModule.init` calls `FitbitGatt.start(context)` and registers a `BaseFitbitGattCallback`. Calling this multiple times or from a different `Application` lifecycle can cause `AlreadyStartedException` or missed callbacks.

8. **GATT server role asymmetry** – In central mode the phone adds `LinkConfigurationService`; in peripheral mode it adds `FitbitGattlinkService` + `GattCacheService`. Using the wrong `isBleCentralRole` will lead to missing services and failed link-up.

9. **MTU handling** – `StackPeer` subtracts `GATT_HEADER_SIZE = 3` in `Stack.updateMtu()`. Hard-coding MTU assumptions elsewhere will fragment or fail large writes.

10. **ProGuard / R8** – `@Keep` is used on `Stack.onDtlsStatusChange`, `Stack.onStackEvent`, and `BootstrapTlsKeyResolver`. Keep rules must preserve all JNI-called methods and `TlsKeyResolver` subclasses.

11. **JitPack availability** – BitGatt is fetched from JitPack (`https://jitpack.io`). If JitPack is unavailable or the tag is removed, builds fail.

12. **Static bootstrap key** – The PSK is public and static. Any production use must replace it with a per-device provisioning flow.

---

## 12. Source File Index

- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/stack/StackConfig.kt`
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/stack/Stack.kt`
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolver.kt`
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolverRegistry.kt`
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolver.kt`
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/HelloTlsKeyResolver.kt`
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/DtlsProtocolStatus.kt`
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpointBuilder.kt`
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/node/BluetoothAddressNodeKey.kt`
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/GoldenGate.kt`
- `GoldenGateBindings/src/main/cpp/stackbuilder/jni_gg_stackbuilder.cpp`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/GoldenGateConnectionManagerModule.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/PeerRole.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/PeerConnector.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/GlobalBluetoothGattInitializer.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/stack/StackPeer.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/stack/StackPeerBuilder.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/Peer.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/NodeMapper.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/Bridge.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/RemoteGattlinkNodeDataSender.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/RemoteGattlinkNodeDataReceiver.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/LinkupWithPeerNodeHandler.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/hub/LinkupWithPeerHubHandler.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/gatt/server/services/gattlink/GattlinkService.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/gatt/server/services/gattlink/FitbitGattlinkService.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/gatt/server/services/gattlink/ReceiveCharacteristic.kt`
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/gatt/server/services/gattlink/TransmitCharacteristic.kt`
- `app/src/main/kotlin/com/fitbit/goldengatehost/AirBridgeOneShotService.kt`
- `app/src/main/kotlin/com/fitbit/goldengatehost/AbstractHostActivity.kt`
- `app/src/main/kotlin/com/fitbit/goldengatehost/AirBridgeRuntime.kt`
- `app/src/main/kotlin/com/fitbit/goldengatehost/DiagnosticTlsIdentityRecorder.kt`
- `gradle.properties`
- `GoldenGateBindings/build.gradle`
- `GoldenGateConnectionManager/build.gradle`
- `RxFitbitGatt/build.gradle`
