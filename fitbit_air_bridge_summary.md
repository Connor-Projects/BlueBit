# Fitbit Air Notification Bridge — Analysis Summary

## Repo URL and clone status
- **URL:** https://github.com/paceaitian/fitbit-air-notification-bridge
- **Clone status:** Cloned successfully to `C:/fitbit_bridge_tmp/fitbit-air-notification-bridge`
- **Latest commit:** `6fec57f Add on-demand Air connection mode`
- **License:** Apache-2.0 (derived from Fitbit Golden Gate)

## Main language(s) and key source files
- **Languages (by tracked file count):** Kotlin (~361), C (~155), C headers (~124), C++ (~64), Python (~45), Markdown (~81).
- **Repo layout:**
  - `platform/android/goldengate/` — Android app and Golden Gate Android modules.
  - `xp/` — Cross-platform Golden Gate C/C++ transport stack (Gattlink, DTLS, CoAP, sockets, stack_builder).
  - `docs/` — Docs including `FITBIT_AIR_PROTOCOL.md`.
  - `tasks/` — Python build/task scripts.
  - `scripts/` — Misc helpers.

### Bridge-specific Android source files (app)
Located under `platform/android/goldengate/app/src/main/kotlin/com/fitbit/goldengatehost/`:

| File | Role |
|------|------|
| `AirBridgeRuntime.kt` | One-time Golden Gate / BitGatt process init |
| `AirBridgeOneShotService.kt` | Foreground service: scan → connect → DTLS → haptic pattern → disconnect |
| `AirNotificationListenerService.kt` | Android `NotificationListenerService`; filters and enqueues alerts |
| `AirHapticPatternExecutor.kt` | Performs toggle/restore haptic groups over CoAP `/settings` |
| `AirDirectSettingsProtocol.kt` | Minimal protobuf codec for haptic intensity read/write |
| `AirBridgeDeviceSettings.kt` | Persists selected Fitbit Air BLE address/name and last status |
| `AirBridgeNotificationSettings.kt` | Parses/stores up to 5 `package=pattern` rules |
| `AirAlarmProtocol.kt` | Minimal protobuf codec for `/alarms` resource (used by debug UI) |
| `SetupActivity.kt` / `scan/ScanActivity.kt` | UI for permissions, device selection, manual test |

### Core Golden Gate modules reused by the bridge
- `GoldenGateBindings/src/main/cpp/stackbuilder/jni_gg_stackbuilder.cpp` — Builds the DSNG stack (Gattlink + netif + DTLS).
- `GoldenGateBindings/src/main/kotlin/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolver.kt` — Static BOOTSTRAP PSK resolver.
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/bt/IPAddresses.kt` — Default link-local addresses.
- `GoldenGateConnectionManager/src/main/java/com/fitbit/goldengate/node/stack/StackPeer.kt` — Creates the BLE + Gattlink + DTLS + CoAP peer.

## Step-by-step bootstrap flow (app start → first CoAP request/response)

1. **Application init**
   - `GoldenGateHostApplication.onCreate()` plants Timber and registers `DiagnosticTlsIdentityRecorder` (diagnostic-only PSK identity logger).
   - `TlsKeyResolverRegistry` already auto-registers `BootstrapTlsKeyResolver()` as the real static BOOTSTRAP key resolver.

2. **Runtime init (on first bridge use)**
   - `AirBridgeRuntime.ensureInitialized()` calls `GoldenGateConnectionManagerModule.init(context, loggingEnabled=true, isBleCentralRole=true)`.
   - This initializes the native Golden Gate loop, BitGatt, and DTLS key resolver registry.

3. **Device selection**
   - `SetupActivity` launches `ScanActivity`, which scans bonded and advertising BLE devices.
   - User picks a Fitbit Air; `AirBridgeDeviceSettings.saveDevice()` stores its MAC/name in `SharedPreferences`.

4. **Notification trigger**
   - `AirNotificationListenerService.onNotificationPosted()` matches the package against saved rules, deduplicates, and calls `AirBridgeOneShotService.enqueue()`.
   - A manual test triggers the same enqueue path with a fake `manual.test` package.

5. **Foreground one-shot service**
   - `AirBridgeOneShotService` starts as a foreground service (`connectedDevice` type) with a persistent notification.
   - It serializes tasks in a queue and grabs a partial wake lock.

6. **BLE scan**
   - `scanForDevice()` scans for the saved MAC address with `SCAN_MODE_LOW_LATENCY`.
   - Timeout: `SCAN_TIMEOUT_MILLIS = 7_000 ms`.
   - On match, the `ScanResult` is registered with BitGatt as a `GattConnection`.

7. **Gattlink + DTLS stack creation**
   - `connectStack()` builds a `StackPeer` with:
     - `BluetoothAddressNodeKey(address)`
     - `PeerRole.Peripheral`
     - `DtlsSocketNetifGattlink` stack config (`DSNG` descriptor)
     - local address `169.254.0.2`, remote `169.254.0.3`, ports `0`
     - `CoapEndpointBuilder`
   - The native stack builder (`jni_gg_stackbuilder.cpp`) adds Gattlink + DTLS client (as peripheral/node) using the static BOOTSTRAP PSK.
   - DTLS handshake timeout bounded by `CONNECT_TIMEOUT_SECONDS = 18`.

8. **CoAP endpoint ready**
   - `peer.connection().firstOrError()` emits when the stack is up, and `peer.stackService` is the `CoapEndpoint`.

9. **First CoAP exchange**
   - `AirHapticPatternExecutor.readIntensity()` sends:
     - `PUT /settings` with body `0x0A 0x01 0x01` (`SETTING_TYPE_HAPTICS`).
   - Fitbit Air returns a `DirectSettings.SettingsResponse`; the parser extracts `haptics_intensity`.
   - This first response confirms the DTLS/CoAP path is alive.

10. **Haptic pattern execution**
    - The executor writes an alternate intensity with `POST /settings`, waits `RESTORE_DELAY_MILLIS = 800 ms`, then writes the original intensity back, retrying the restore up to `RESTORE_RETRIES = 2`.
    - Multi-group patterns add the configured inter-group delays.
    - Final `readIntensity()` verifies the setting was restored.

11. **Teardown**
    - `releasePeer()` closes the Golden Gate peer and BLE connection.
    - `RELEASE_SETTLE_MILLIS = 700 ms` delay lets the radio settle before Google Health reconnects.
    - Service stops foreground and stops itself if the queue is empty.

## Key constants table

| Category | Constant | Value / Notes |
|----------|----------|---------------|
| **App** | Application ID | `io.github.paceaitian.fitbitairbridge` |
| | DEV_VERSION / build | `10014` (README references v10014) |
| | ABI | `arm64-v8a` only (per README) |
| **BLE / Gattlink** | Gattlink service UUID | `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` |
| | Gattlink RX char | `ABBAFF01-E56A-484C-B832-8B17CF6CBFE8` |
| | Gattlink TX char | `ABBAFF02-E56A-484C-B832-8B17CF6CBFE8` |
| | Short Gattlink service (16-bit) | `0000FD62-0000-1000-8000-00805F9B34FB` |
| | Link Configuration service | `ABBAFC00-E56A-484C-B832-8B17CF6CBFE8` |
| | Link Configuration chars | `ABBAFC01`…`ABBAFC03` … |
| | Link Status service | `ABBAFD00-E56A-484C-B832-8B17CF6CBFE8` |
| | Link Status chars | `ABBAFD01`…`ABBAFD03` |
| | GATT Confirmation service | `AC2F0045-8182-4BE5-91E0-2992E6B40EBB` |
| | Ephemeral char pointer | `AC2F0145-8182-4BE5-91E0-2992E6B40EBB` |
| | GATT Confirmation char | `AC2F0345-8182-4BE5-91E0-2992E6B40EBB` |
| | Client Config Descriptor | `00002902-0000-1000-8000-00805f9b34fb` |
| | Generic Attribute Service | `00001801-0000-1000-8000-00805f9b34fb` |
| | Service Changed char | `00002a05-0000-1000-8000-00805f9b34fb` |
| | Mobile Data Key service (debug) | `26F33A00-52A8-414B-99A2-1DB75C99C032` |
| **DTLS** | Bootstrap identity | `"BOOTSTRAP"` (9 ASCII bytes) |
| | Bootstrap PSK (16 bytes) | `81 06 54 e3 36 ad ca b0 a0 3c 60 f7 4a a0 b6 fb` |
| | Cipher suites | `TLS_PSK_WITH_AES_128_CCM`, `TLS_PSK_WITH_AES_128_GCM_SHA256`, `TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256` |
| | Key size | `GG_STACK_DTLS_KEY_SIZE = 16` |
| **Network/CoAP** | Local IP (server/hub) | `169.254.0.2` |
| | Remote IP (client/node) | `169.254.0.3` |
| | Default UDP/CoAP port | `5683` |
| | Stack descriptor | `DSNG` (Dtls-Socket-Netif-Gattlink) |
| | CoAP ACK timeout | `5000 ms` (randomized up to 1.5×) |
| | CoAP max retransmits | `4` (`GG_COAP_DEFAULT_MAX_RETRANSMIT`) |
| | Gattlink fragment size | `20` bytes (`GG_STACK_BUILDER_DEFAULT_GATTLINK_FRAGMENT_SIZE`) |
| | IP MTU | `1280` bytes |
| | Gattlink probe window | `1000 ms` |
| | Gattlink probe samples | `50` |
| | Gattlink probe threshold | `400` byte·seconds |
| **Haptics protocol** | `SETTING_TYPE_HAPTICS` request body | `0x0A 0x01 0x01` |
| | Example HIGH response | `0A 04 0A 02 08 03` |
| | Intensity values | `UNSPECIFIED=0`, `OFF=1`, `LOW=2`, `HIGH=3` |
| | Restore delay | `800 ms` |
| | Restore retries | `2` |
| | Pattern group delays | `single=[]`, `double=[650]`, `triple=[650,650]`, `long_gap=[1800]`, `urgent=[450,1350,450]` |
| **Bridge timing** | Scan timeout | `7_000 ms` |
| | Connect/DTLS timeout | `18 s` |
| | Release settle delay | `700 ms` |
| | Wake lock timeout | `120_000 ms` |
| | Retry delays | `[2_000 ms, 5_000 ms]` (max 3 attempts total) |
| | Max task queue size | `10` |
| | Duplicate suppression window | `1_500 ms` |
| | Max recent notifications | `512` |
| | Max rules | `5` |
| | Default rules text | `com.tencent.mm=single` |
| | Foreground notif ID | `14001` |
| | Channel ID | `air_bridge_connection` |

## Relevant code snippets

### AirBridgeOneShotService — scan → connect → DTLS → execute
```kotlin
private fun executeOnce(trigger: AirNotificationTrigger): Single<AirHapticExecutionResult> {
    val address = AirBridgeDeviceSettings.loadAddress(this)
        ?: return Single.error(IllegalStateException("尚未选择 Fitbit Air"))
    return Single.fromCallable {
        AirBridgeRuntime.ensureInitialized(this)
        address
    }.flatMap { scanForDevice(it) }
        .flatMap { connection -> connectStack(connection.device.address) }
        .flatMap { endpoint ->
            updateStatus("已建立 DTLS，正在执行 ${trigger.pattern.wireName}")
            AirHapticPatternExecutor.execute(endpoint, trigger.pattern)
        }
        .flatMap { result ->
            releasePeer()
            Single.timer(RELEASE_SETTLE_MILLIS, TimeUnit.MILLISECONDS).map { result }
        }
        .doOnError { releasePeer() }
}
```

### connectStack() — DSNG peer creation
```kotlin
private fun connectStack(address: String): Single<CoapEndpoint> {
    updateStatus("已发现 Air，正在建立 Gattlink / DTLS")
    DiagnosticTlsIdentityRecorder.clear()
    val stackConfig = DtlsSocketNetifGattlink(
        Inet4Address.getByName(DEFAULT_SERVER_ADDRESS) as Inet4Address,
        0,
        Inet4Address.getByName(DEFAULT_CLIENT_ADDRESS) as Inet4Address,
        0
    )
    val peer = StackPeer(
        BluetoothAddressNodeKey(address),
        PeerRole.Peripheral,
        stackConfig,
        CoapEndpointBuilder(),
        { connection -> PeripheralConnectionChangeListener().register(connection) },
        { stack -> stack.dtlsEventObservable }
    )
    activePeer = peer
    return peer.connection()
        .firstOrError()
        .timeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .map { peer.stackService }
}
```

### AirHapticPatternExecutor — toggle/restore primitive
```kotlin
private fun executeGroup(
    endpoint: CoapEndpoint,
    originalIntensity: Int,
    alternateIntensity: Int
): Single<AirHapticGroupResult> =
    writeIntensity(endpoint, alternateIntensity).flatMap { alternateResponse ->
        require(alternateResponse.responseCode.ok()) {
            "Alternate write returned ${alternateResponse.responseCode}"
        }
        Single.timer(RESTORE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .flatMap {
                writeIntensity(endpoint, originalIntensity)
                    .map { restoreResponse ->
                        require(restoreResponse.responseCode.ok()) {
                            "Restore write returned ${restoreResponse.responseCode}"
                        }
                        restoreResponse
                    }
                    .retry(RESTORE_RETRIES.toLong())
            }
            .map { restoreResponse -> AirHapticGroupResult(alternateResponse, restoreResponse) }
    }
```

### AirDirectSettingsProtocol — protobuf constants
```kotlin
const val HAPTICS_UNSPECIFIED = 0
const val HAPTICS_OFF = 1
const val HAPTICS_LOW = 2
const val HAPTICS_HIGH = 3

fun encodeHapticsSettingsRequest(): ByteArray = byteArrayOf(0x0A, 0x01, 0x01)

fun encodeHapticsSettingsResponse(value: Int): ByteArray {
    require(value in HAPTICS_OFF..HAPTICS_HIGH) { "Unsupported haptics intensity $value" }
    return byteArrayOf(0x0A, 0x04, 0x0A, 0x02, 0x08, value.toByte())
}
```

### AirNotificationListenerService — filtering and enqueue
```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification?) {
    sbn ?: return
    if (sbn.packageName == packageName) return
    val rule = AirBridgeNotificationSettings.findRule(this, sbn.packageName) ?: return

    val notification = sbn.notification ?: return
    if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
    if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

    // ... content signature dedup ...

    val trigger = AirNotificationTrigger(rule.packageName, rule.pattern)
    val accepted = AirBridgeOneShotService.enqueue(this, trigger)
    Timber.i("Air notification bridge package=%s pattern=%s accepted=%s",
        rule.packageName, rule.pattern.wireName, accepted)
}
```

### BootstrapTlsKeyResolver — static BOOTSTRAP PSK
```kotlin
internal val BOOTSTRAP_KEY_ID = "BOOTSTRAP".toByteArray()
internal val BOOTSTRAP_KEY = byteArrayOf(
    0x81.toByte(), 0x06, 0x54, 0xe3.toByte(), 0x36, 0xad.toByte(),
    0xca.toByte(), 0xb0.toByte(), 0xa0.toByte(), 0x3c, 0x60,
    0xf7.toByte(), 0x4a, 0xa0.toByte(), 0xb6.toByte(), 0xfb.toByte()
)

internal class BootstrapTlsKeyResolver : TlsKeyResolver() {
    override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? {
        return when {
            keyId.contentEquals(BOOTSTRAP_KEY_ID) -> BOOTSTRAP_KEY
            else -> null
        }
    }
}
```

### Gattlink service UUIDs (Kotlin)
```kotlin
// GattlinkService.kt
val uuid: UUID = UUID.fromString("ABBAFF00-E56A-484C-B832-8B17CF6CBFE8")

// ReceiveCharacteristic.kt
val uuid: UUID = UUID.fromString("ABBAFF01-E56A-484C-B832-8B17CF6CBFE8")

// TransmitCharacteristic.kt
val uuid: UUID = UUID.fromString("ABBAFF02-E56A-484C-B832-8B17CF6CBFE8")
```

## Caveats / device-specific notes

- **Alpha / single-device validation.** README explicitly states the protocol has been validated on **one Fitbit Air only**.
- **Not an official product.** Independent project; Fitbit/Google trademarks acknowledged in `NOTICE`.
- **Single-owner transport.** Fitbit Air accepts only one active Gattlink/DTLS owner. The bridge cannot stay connected while Google Health owns the device. v10014 therefore uses **one-shot time-sharing** (connect, run pattern, disconnect) instead of a persistent connection.
- **Google Health contention.** If Google Health is actively syncing, alerts are delayed until the Air advertises again; the bridge retries up to 3 times.
- **BLE address rotation.** The saved MAC address may become stale; the user must re-select the Air if address rotation occurs.
- **ABI limitation.** Only `arm64-v8a` is built at present.
- **Haptics are not arbitrary waveforms.** Patterns are composed of verified **toggle/restore intensity groups** (`read → POST alt → wait 800 ms → POST original → verify`).
- **v10014 on-demand path** is implemented and locally tested but still needs wider device validation alongside Google Health.
- **No proprietary assets.** The repo contains no Google Health APK, extracted code, credentials, or per-user device keys. The only key material is the **public/static BOOTSTRAP PSK** shipped with Fitbit Golden Gate.
- **Pairing / bonding.** The bridge itself does not implement a custom pairing/bonding protocol. It relies on Android Bluetooth bonding (the scan UI shows bonded devices and may call `createBond()`), and the DTLS session uses the pre-shared BOOTSTRAP identity rather than a per-bond key.

## Reusability for Inspire 3 pairing

**Not directly reusable.** The bridge is built around Fitbit Air-specific behavior:

- It targets the Fitbit Air `/settings` haptics resource with a hand-rolled protobuf codec; other devices may expose different resources or different protobuf layouts.
- It assumes the **public Golden Gate BOOTSTRAP PSK** (`BOOTSTRAP` identity + 16-byte static key). A retail Inspire 3 is unlikely to accept this identity/key once it has been paired to an official app, which typically provisions a per-device or per-session key.
- The BLE service/characteristic UUIDs (`ABBAFFxx`, `ABBAFCxx`, `ABBAFDxx`, `AC2Fxxxx`) are Golden Gate plumbing, so the low-level transport stack code could be reused, but the **higher-level pairing flow, key provisioning, and CoAP endpoints would differ**.
- The README/docs explicitly call out “Fitbit Air” and “one active Gattlink/DTLS owner,” assumptions that may not match Inspire 3’s companion-app behavior.

**Conclusion:** The transport scaffolding (BLE Gattlink + DTLS + CoAP + Golden Gate bindings) is a useful reference for any Golden Gate-based Fitbit device, but the **pairing/key material and the haptic notification protocol are Air-specific and would need reverse engineering / validation on Inspire 3** before reuse.
