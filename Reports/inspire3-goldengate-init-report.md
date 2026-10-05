# BlueBit — Inspire 3 GoldenGate Native Stack Initialization Report

**Date:** 2026-08-31
**Status:** IMPLEMENTATION COMPLETE — AWAITING DEVICE RECONNECTION FOR EXPERIMENT

---

## 1. Objective

Reproduce the Fitbit APK's GoldenGate native stack initialization path against the Inspire 3 to observe the actual Gattlink reset/initialization sequence.

---

## 2. APK Analysis Summary

### 2.1 Native Library

| Property | Value |
|----------|-------|
| Library | `libxp.so` |
| Size | 426,528 bytes (416.5 KB) |
| Architecture | AArch64 (arm64-v8a) |
| JNI Exports | 39 functions |
| Stripping | `.symtab` absent; `.dynsym` present; `.rodata` strings intact |

### 2.2 Stack Configuration

The APK contains multiple `StackConfig` subclasses:

| Config | Descriptor | Layers |
|--------|-----------|--------|
| `GattlinkStackConfig` | `"G"` | Gattlink only (NO DTLS) |
| `SocketNetifGattlink` | `"SNG"` | Socket + IPv4 + Gattlink |
| `DtlsSocketNetifGattlink` | `"DSNG"` | DTLS + Socket + IPv4 + Gattlink |

**Key Finding:** `GattlinkStackConfig("G")` creates a **Gattlink-only stack with no DTLS**, CoAP, or IPv4 framing. This is the minimal configuration for testing Gattlink transport.

### 2.3 Initialization Sequence (from APK)

```
1. System.loadLibrary("xp")
2. GoldenGate.Companion.init()
   ├─ initModulesJNI()
   ├─ registerLoggerJNI()
   └─ RunLoop.startAndWaitUntilReady()
3. TxSink.create()          ← JNI callback: putData(byte[])
4. RxSource.create()        ← JNI method: receiveData(byte[], ptr)
5. Bridge(TxSink, RxSource) ← Wire TX/RX
6. Stack.create(
     NodeKey="device_address",
     configDescriptor="G",
     isNode=false,
     transportSinkPtr=TxSink.thisPointer,
     transportSourcePtr=RxSource.thisPointer,
     localAddress=0.0.0.0, localPort=0,
     remoteAddress=0.0.0.0, remotePort=0,
     tlsKeyResolver=null,
     gattlinkRxWindowSize=0,
     gattlinkTxWindowSize=0,
     gattlinkBufferThresholdHysteresis=0.0,
     gattlinkExpectedAckTimeout=0
   )
7. Stack.attachEventListener()
8. Bridge.start()
9. Stack.start()            ← "starting gattlink session"
                             ← GG_GattlinkProtocol_SendResetPacket
                             ← TxSink.putData([0x80, ...]) ← BLE ABBAFF01
```

### 2.4 BLE Transport Wiring

**TX path (native → BLE):**
```
Native: GG_GattlinkGenericClient_SendRawData(byte[])
    ↓ JNI callback
Java: TxSink.putData(byte[])
    ↓ RxJava Observable (APK) / Kotlin callback (BlueBit)
Java: Bridge.sendToNode(byte[])
    ↓ NodeDataSenderProvider
Java: RemoteGattlinkNodeDataSender.send(byte[])
    ↓ GattCharacteristicWriter
BLE: WRITE_NO_RESPONSE to ABBAFF01
```

**RX path (BLE → native):**
```
BLE: Notification from ABBAFF02
Java: BluetoothGattCallback.onCharacteristicChanged()
Java: Bridge.sendToStack(byte[])
Java: RxSource.receiveData(byte[])
    ↓ JNI
Native: GG_GattlinkGenericClient_NotifyIncomingDataAvailable()
```

---

## 3. Implementation

### 3.1 Files Created

| File | Description |
|------|-------------|
| `com/fitbit/goldengate/bindings/GoldenGateNativeException.kt` | Exception type |
| `com/fitbit/goldengate/bindings/GoldenGateNativeResult.kt` | Result codes (SUCCESS=0, FAILURE=-1, WOULD_BLOCK=-2) |
| `com/fitbit/goldengate/bindings/NativeReference.kt` | Interface with `getThisPointer()` |
| `com/fitbit/goldengate/bindings/NativeReferenceCreationResult.kt` | JNI creation result wrapper |
| `com/fitbit/goldengate/bindings/GoldenGate.kt` | Library loader, `init()`, `getVersion()` |
| `com/fitbit/goldengate/bindings/RunLoop.kt` | Native thread loop |
| `com/fitbit/goldengate/bindings/io/TxSink.kt` | TX sink with `putData()` callback |
| `com/fitbit/goldengate/bindings/io/RxSource.kt` | RX source with `receiveData()` |
| `com/fitbit/goldengate/bindings/Bridge.kt` | BLE-native data bridge |
| `com/fitbit/goldengate/bindings/node/NodeKey.kt` | Node key interface |
| `com/fitbit/goldengate/bindings/node/BluetoothAddressNodeKey.kt` | MAC address key |
| `com/fitbit/goldengate/bindings/stack/StackConfig.kt` | Config base class |
| `com/fitbit/goldengate/bindings/stack/GattlinkStackConfig.kt` | `"G"` config singleton |
| `com/fitbit/goldengate/bindings/stack/Stack.kt` | Native stack with `start()` |
| `bluebit/bluetooth/android/FitbitGoldenGateDiagnostic.kt` | Full diagnostic experiment |

### 3.2 UI Integration

- Added **"Test GoldenGate Native"** button to `DeviceDetailScreen`
- Wired through `BlueBitAppShell` → `MainActivity.testGoldenGate(address)`

### 3.3 Native Library Placement

```
app/src/androidMain/jniLibs/arm64-v8a/libxp.so
```

Gradle automatically bundles `.so` files from `jniLibs/` into the APK.

### 3.4 Build Status

```
BUILD SUCCESSFUL in 7s
37 actionable tasks: 6 executed, 31 up-to-date
```

**0 errors, 0 warnings** (beyond deprecation notices for Android API).

---

## 4. PSK / Key Material Requirements

| Aspect | Status |
|--------|--------|
| DTLS | **NOT REQUIRED** — using `GattlinkStackConfig("G")` |
| PSK identity | N/A |
| PSK key | N/A |
| Hello key | N/A |
| Bootstrap key | N/A |
| TLS-PSK cipher suites | N/A |

The `"G"` descriptor creates a Gattlink-only stack with **no encryption layer**. DTLS/CoAP/IPv4 are only present in `"DSNG"` and `"SNG"` configurations.

---

## 5. Expected Experiment Behavior

When **"Test GoldenGate Native"** is tapped on the Inspire 3 detail screen:

1. **GoldenGate.init()** loads `libxp.so`, starts RunLoop thread
2. **BLE connect** → request MTU=185 → discover services
3. **Enable notifications** on ABBAFF02 (CCC write `{0x01, 0x00}`)
4. **Create Stack** with `configDescriptor="G"`, `NodeKey=MAC`, `TxSink`/`RxSource` pointers
5. **Stack.start()** → native prints `"starting gattlink session"`
6. **Native state machine** transitions INIT → sends reset packet
7. **TxSink.putData()** callback fires with byte array (likely starting with `0x80`)
8. **BlueBit writes** the bytes to ABBAFF01 via `BluetoothGatt.writeCharacteristic()`
9. **Inspire 3 responds** on ABBAFF02 (notification)
10. **BlueBit feeds** received bytes to `RxSource.receiveData()`
11. **Native processes** → state machine advances toward READY

### 5.1 Key Log Tags to Watch

| Tag | Source | Expected Content |
|-----|--------|------------------|
| `BlueBitGG` | GoldenGate.kt | init, version, RunLoop status |
| `BlueBitGG_TxSink` | TxSink.kt | Creation, TX bytes hex dump |
| `BlueBitGG_RxSource` | RxSource.kt | Creation, RX forward result |
| `BlueBitGG_Bridge` | Bridge.kt | Start/stop, TX/RX data flow |
| `BlueBitGG_Stack` | Stack.kt | Creation, start(), events |
| `BlueBitGG_Diag` | FitbitGoldenGateDiagnostic.kt | Experiment lifecycle |

---

## 6. Current Status

| Milestone | Status |
|-----------|--------|
| APK analysis | ✅ Complete |
| Native library identified | ✅ `libxp.so` |
| JNI method signatures recovered | ✅ 39 exports mapped |
| Gattlink-only config found | ✅ `GattlinkStackConfig("G")` |
| Wrapper classes implemented | ✅ 15 files |
| UI button added | ✅ "Test GoldenGate Native" |
| Native library bundled | ✅ `jniLibs/arm64-v8a/libxp.so` |
| Build | ✅ Successful |
| Device connected | ❌ **DISCONNECTED** (ADB shows no devices) |
| Experiment run | ⏳ Pending device reconnection |
| Logs captured | ⏳ Pending |
| INIT→READY verified | ⏳ Pending |

---

## 7. Next Steps

1. **Reconnect device** to PC via USB
2. **Install APK**: `adb install -r app-debug.apk`
3. **Launch app** and tap **"Scan for Devices"**
4. **Select Inspire 3** and tap **"Test GoldenGate Native"**
5. **Wait 60 seconds** for timeout or state transition
6. **Pull logs**: `adb logcat -d -s BlueBitGG*:I > goldengate_experiment.log`

---

## 8. Risk Assessment

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| JNI method signatures mismatch | Medium | Used exact APK-derived signatures; if crash, check `javah` output |
| `TxSink.create()` callback method not found | Medium | Method name `"putData"` and sig `"([B)V"` match APK pattern |
| RunLoop thread conflict | Low | APK starts one RunLoop; BlueBit does the same |
| Native crash on Stack.create() | Medium | Pointers passed directly; may need exact struct layout |
| Device requires bonded connection | Unknown | Experiment uses unbonded BLE; if fails, try bonded mode |

---

## 9. Files Referenced

| File | Description |
|------|-------------|
| `Decoded/Fitbit-arm64/lib/arm64-v8a/libxp.so` | GoldenGate native library |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/GoldenGate.java` | Loader |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/stack/Stack.java` | Stack controller |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/stack/GattlinkStackConfig.java` | `"G"` config |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/io/TxSink.java` | TX sink |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/io/RxSource.java` | RX source |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/node/Bridge.java` | BLE bridge |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/node/stack/StackPeer.java` | Stack builder |

---

*Report compiled after full APK analysis and implementation. Experiment pending device reconnection.*
