# BlueBit — Inspire 3 Gattlink Native Library Analysis

**Date:** 2026-08-31
**Target:** Fitbit Inspire 3 (D8:9E:C1:BA:3D:E4)
**APK:** Fitbit-base.apk (decompiled via JADX 1.5.6)
**Library:** `libxp.so` (GoldenGate native implementation)

---

## Executive Summary

| Finding | Confidence |
|---------|------------|
| GoldenGate library = `libxp.so` | **CONFIRMED** |
| Gattlink protocol fully implemented in native code | **CONFIRMED** |
| Reset/init packet header = `0x80` (control, no ACK, PSN=0) | **HIGH CONFIDENCE** |
| State machine: INIT → (reset handshake) → READY | **CONFIRMED** |
| DTLS + CoAP + IPv4 framing in same `.so` | **CONFIRMED** |
| MbedTLS for DTLS (TLS-PSK) | **CONFIRMED** |
| Exact initialization byte sequence | **UNKNOWN** (stripped binary) |
| ABBAFF01/ABBAFF02 UUIDs in native code | **NOT FOUND** (passed from Java) |

**Classification: STATIC_ANALYSIS_LIMITATION**

The binary is stripped. While the protocol architecture, state machine, and function names are fully exposed via `.rodata` strings, the exact byte-level frame construction cannot be determined statically. A BLE sniffer capture of the genuine Fitbit app handshake is required.

---

## 1. Native Library Inventory

| ABI | Filename | Size | Architecture | Notes |
|-----|----------|------|--------------|-------|
| arm64-v8a | `libxp.so` | 426,528 B (416.5 KB) | AArch64 | **GoldenGate library** |
| arm64-v8a | `libalexa-android-native.so` | 3,960,816 B | AArch64 | Alexa voice (unrelated) |
| arm64-v8a | `libandroidx.graphics.path.so` | 9,952 B | AArch64 | AndroidX graphics |
| arm64-v8a | `libdatastore_shared_counter.so` | 10,648 B | AArch64 | DataStore |
| arm64-v8a | `libimage_processing_util_jni.so` | 32,528 B | AArch64 | Image processing |
| arm64-v8a | `libminimp3.so` | 69,456 B | AArch64 | MP3 decoder |
| arm64-v8a | `libnative_crash_handler_jni.so` | 53,064 B | AArch64 | Crash handler |
| arm64-v8a | `libopuscodec.so` | 249,720 B | AArch64 | Opus codec |
| arm64-v8a | `libsurface_util_jni.so` | 4,896 B | AArch64 | Surface utils |

**GoldenGate identification:**
- Source: `GoldenGate.kt:260` — `System.loadLibrary("xp")` as fallback
- Build path in `.rodata`: `//third_party/goldengate/platform/android/goldengate/GoldenGateBindings:libxp.so`

---

## 2. JNI Boundary

### 2.1 Library Loading

```kotlin
// GoldenGate.kt
System.loadLibrary("xp")  // Fallback when config loading fails
```

### 2.2 JNI Export Table (39 functions)

**Core / Initialization:**
| Java Class | Native Method | JNI Symbol |
|------------|--------------|------------|
| `GoldenGate` | `getVersionJNI()` | `Java_com_fitbit_goldengate_bindings_GoldenGate_getVersionJNI` |
| `GoldenGate` | `initModulesJNI()` | `Java_com_fitbit_goldengate_bindings_GoldenGate_initModulesJNI` |
| `GoldenGate` | `pingJNI()` | `Java_com_fitbit_goldengate_bindings_GoldenGate_pingJNI` |
| `GoldenGate` | `registerLoggerJNI()` | `Java_com_fitbit_goldengate_bindings_GoldenGate_registerLoggerJNI` |
| `RunLoop` | `startLoopJNI()` | `Java_com_fitbit_goldengate_bindings_RunLoop_startLoopJNI` |
| `RunLoop` | `stopLoopJNI()` | `Java_com_fitbit_goldengate_bindings_RunLoop_stopLoopJNI` |
| `RunLoop` | `destroyLoopJNI()` | `Java_com_fitbit_goldengate_bindings_RunLoop_destroyLoopJNI` |

**Stack / Gattlink:**
| Java Class | Native Method | JNI Symbol |
|------------|--------------|------------|
| `Stack` | `create(...)` | `Java_com_fitbit_goldengate_bindings_stack_Stack_create` |
| `Stack` | `destroy(jlong)` | `Java_com_fitbit_goldengate_bindings_stack_Stack_destroy` |
| `Stack` | `start(jlong)` | `Java_com_fitbit_goldengate_bindings_stack_Stack_start` |
| `Stack` | `updateMtu(int, jlong)` | `Java_com_fitbit_goldengate_bindings_stack_Stack_updateMtu` |
| `Stack` | `attachEventListener(Class, jlong)` | `Java_com_fitbit_goldengate_bindings_stack_Stack_attachEventListener` |
| `Stack` | `getTopPortAsDataSink(jlong)` | `Java_com_fitbit_goldengate_bindings_stack_Stack_getTopPortAsDataSink` |
| `Stack` | `getTopPortAsDataSource(jlong)` | `Java_com_fitbit_goldengate_bindings_stack_Stack_getTopPortAsDataSource` |

**TxSink (ABBAFF01 WRITE path):**
| Java Class | Native Method | JNI Symbol |
|------------|--------------|------------|
| `TxSink` | `create(Class, String, String)` | `Java_com_fitbit_goldengate_bindings_io_TxSink_create` |
| `TxSink` | `destroy(jlong)` | `Java_com_fitbit_goldengate_bindings_io_TxSink_destroy` |
| `TxSink` | `notifyListener(jlong)` | `Java_com_fitbit_goldengate_bindings_io_TxSink_notifyListener` |

**RxSource (ABBAFF02 READ path):**
| Java Class | Native Method | JNI Symbol |
|------------|--------------|------------|
| `RxSource` | `create()` | `Java_com_fitbit_goldengate_bindings_io_RxSource_create` |
| `RxSource` | `destroy(jlong)` | `Java_com_fitbit_goldengate_bindings_io_RxSource_destroy` |
| `RxSource` | `receiveData(byte[], jlong)` | `Java_com_fitbit_goldengate_bindings_io_RxSource_receiveData` |

**CoAP / DTLS:**
| Java Class | Native Method | JNI Symbol |
|------------|--------------|------------|
| `CoapEndpoint` | `create()` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_create` |
| `CoapEndpoint` | `attach(jlong, jlong, jlong)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_attach` |
| `CoapEndpoint` | `detach(jlong, jlong)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_detach` |
| `CoapEndpoint` | `destroy(jlong)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_destroy` |
| `CoapEndpoint` | `asDataSink(jlong)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_asDataSink` |
| `CoapEndpoint` | `asDataSource(jlong)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_asDataSource` |
| `CoapEndpoint` | `addResourceHandler(...)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_addResourceHandler` |
| `CoapEndpoint` | `removeResourceHandler(jlong)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_removeResourceHandler` |
| `CoapEndpoint` | `responseFor(...)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_responseFor` |
| `CoapEndpoint` | `responseForBlockwise(...)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_responseForBlockwise` |
| `CoapEndpoint` | `attachFilter(jlong, jlong)` | `Java_com_fitbit_goldengate_bindings_coap_CoapEndpoint_attachFilter` |
| `CoapGroupRequestFilter` | `create()` | `Java_com_fitbit_goldengate_bindings_coap_CoapGroupRequestFilter_create` |
| `CoapGroupRequestFilter` | `destroy(jlong)` | `Java_com_fitbit_goldengate_bindings_coap_CoapGroupRequestFilter_destroy` |
| `CoapGroupRequestFilter` | `setGroup(byte, jlong)` | `Java_com_fitbit_goldengate_bindings_coap_CoapGroupRequestFilter_setGroup` |
| `SingleCoapResponseListener` | `cancelResponse(jlong)` | `Java_com_fitbit_goldengate_bindings_coap_SingleCoapResponseListener_cancelResponse` |
| `BlockwiseCoapResponseListener` | `cancelResponseForBlockwise(jlong, boolean)` | `Java_com_fitbit_goldengate_bindings_coap_block_BlockwiseCoapResponseListener_cancelResponseForBlockwise` |
| `ExtendedErrorDecoder` | `decode(byte[])` | `Java_com_fitbit_goldengate_bindings_coap_data_ExtendedErrorDecoder_decode` |
| `Logger` | `createLoggerJNI(...)` | `Java_com_fitbit_goldengate_bindings_logging_Logger_createLoggerJNI` |
| `Logger` | `destroyLoggerJNI(jlong)` | `Java_com_fitbit_goldengate_bindings_logging_Logger_destroyLoggerJNI` |

### 2.3 Call Chain to First Gattlink TX

```
Java: Stack.start()
    ↓ JNI
Native: Java_com_fitbit_goldengate_bindings_stack_Stack_start
    ↓
Native: GG_Stack_Start
    ↓
Native: starting gattlink session
    ↓
Native: GG_GattlinkGenericClient_NotifySessionReady
    ↓
Native: GG_GattlinkProtocol_SendNextPackets
    ↓
Native: GG_GattlinkProtocol_PrepareNextPacket
    ↓
Native: [frame construction — stripped]
    ↓
Native: sending %u bytes to the transport
    ↓
Native: GG_GattlinkGenericClient_SendRawData
    ↓
Native: Calling into Java PutData callback
    ↓ JNI callback
Java: TxSink.putData(byte[])
    ↓
Java: GattCharacteristicWriter.write(ABBAFF01, ...)
    ↓ BLE
Inspire 3: ABBAFF01 WRITE_NO_RESPONSE
```

**Confidence:** HIGH — derived from string cross-references and Java source structure.

---

## 3. Symbol Analysis

### 3.1 Stripping Status

| Section | Status |
|---------|--------|
| `.dynsym` | Present (144 entries, 141 global) |
| `.symtab` | **ABSENT** (stripped) |
| Debug info | **ABSENT** |
| Function names in `.text` | **ABSENT** (only via string references) |
| `.rodata` strings | **INTACT** (1160 strings) |

**Conclusion:** The library is **fully stripped** but **not obfuscated**. All diagnostic strings remain, exposing internal function names and protocol behavior.

### 3.2 Dynamic Symbols

- Total: 144 entries
- Global exports: 141
- JNI functions: 39
- Standard library imports: 102 (`malloc`, `free`, `pthread_mutex_lock`, etc.)

No internal GoldenGate function names in `.dynsym` — only JNI entry points and libc imports are exported.

---

## 4. String Analysis

### 4.1 Gattlink Protocol Strings

| Offset | String | Significance |
|--------|--------|--------------|
| 0x00636C | `GG_GattlinkProtocol_SendResetPacket` | Reset packet sender |
| 0x009EF6 | `GG_GattlinkProtocol_SendResetCompletePacket` | Reset complete sender |
| 0x00461E | `GG_GattlinkProtocol_HandleControlPacket` | Control packet handler |
| 0x00B697 | `GG_GattlinkProtocol_HandleDataPacket` | Data packet handler |
| 0x006C5D | `GG_GattlinkProtocol_PrepareNextPacket` | Frame builder |
| 0x004E55 | `GG_GattlinkProtocol_SendNextPackets` | TX dispatcher |
| 0x0062FD | `GG_GattlinkProtocol_ConsumeIncomingData` | RX dispatcher |
| 0x00A346 | `GG_GattlinkProtocol_HandleIncomingRawData` | Raw data handler |
| 0x0063AE | `gg.xp.gattlink.protocol` | Log tag |
| 0x00AE95 | `gg.xp.gattlink.generic-client` | Log tag |

### 4.2 State Machine Strings

| Offset | String | Significance |
|--------|--------|--------------|
| 0x00A695 | `starting gattlink session` | Session start |
| 0x005FCE | `resetting session` | Session reset |
| 0x005F22 | `Not in READY state (%d), skipping packet transmission` | State gate |
| 0x00AF4E | `ignoring reset, we're already in the INIT state` | INIT state |
| 0x004646 | `unexpected reset complete received while in state %d ... ignoring` | State validation |
| 0x0058D0 | `Preparing packet with ACK PSN %u` | ACK in frame |
| 0x0030B6 | `Preparing packet without ACK` | No ACK |
| 0x007F68 | `MTU %u is too small, aborting packet preparation` | MTU check |

### 4.3 Frame Construction Evidence

| Offset | String | Significance |
|--------|--------|--------------|
| 0x009E6F | `sending %u bytes to the transport` | Transport write |
| 0x003326 | `sending packet, ADDR=%d.%d.%d.%d, size=%d` | IPv4 packet send |
| 0x0037D8 | `ACK timer fired` | ACK timeout |
| 0x0037FB | `acking now: %u unacked packets > window/2` | ACK policy |
| 0x004B5B | `Received Ack PSN: %d for %d byte(s), Next expected Ack PSN: %d` | ACK processing |
| 0x004E2E | `Received PSN (%d) != Expected PSN (%d)` | PSN validation |
| 0x007970 | `Received %d Byte(s): 0x%02hhx... PSN: %d, Next expected PSN: %d` | Packet log |
| 0x00B9A | `got packet header, packet_size=%u` | Header parser |
| 0x00350 | `this doesn't look like a valid packet` | Validation failure |
| 0x00420E | `%u unacked packets` | Window tracking |
| 0x004688 | `window full (%u packets in flight)` | Flow control |
| 0x007F33 | `Ack: PSN=%d` | ACK format |

### 4.4 DTLS / Crypto Strings

| Offset | String | Significance |
|--------|--------|--------------|
| 0x003905 | `TLS-PSK-WITH-AES-256-CCM` | Cipher suite |
| 0x004792 | `TLS-PSK-WITH-AES-256-CBC-SHA384` | Cipher suite |
| 0x006D4E | `TLS-PSK-WITH-AES-128-CCM-8` | Cipher suite |
| 0x004BF4 | `TLS-PSK-WITH-AES-128-CBC-SHA` | Cipher suite |
| 0x00596D | `TLS-PSK-WITH-AES-128-CCM` | Cipher suite |
| 0x005FE0 | `TLS-PSK-WITH-AES-256-CBC-SHA384` | Cipher suite |
| 0x007257 | `TLS-PSK-WITH-AES-256-CBC-SHA` | Cipher suite |
| 0x007983 | `TLS-PSK-WITH-AES-128-GCM-SHA256` | Cipher suite |
| 0x008FD2 | `TLS-PSK-WITH-AES-256-GCM-SHA384` | Cipher suite |
| 0x009F22 | `TLS-ECDHE-PSK-WITH-AES-256-CBC-SHA` | Cipher suite |
| 0x009F48 | `TLS-ECDHE-PSK-WITH-AES-128-CBC-SHA` | Cipher suite |
| 0x0038B3 | `GG_TlsKeyResolver_ResolveKey returned %d` | PSK resolution |
| 0x00422E | `resolving PSK identity, size=%u` | PSK identity |
| 0x005911 | `GG_TlsKeyResolver_ResolveKey failed to resolve key with identity %s with %d` | PSK failure |
| 0x006849 | `TLSv1.3` | TLS version |
| 0x008FC9 | `DTLSv1.2` | DTLS version |
| 0x0033C8 | `mbedtls wants to read up to %u bytes` | MbedTLS integration |
| 0x0052DA | `mbedtls_ssl_handshake_step returned MBEDTLS_ERR_SSL_WANT_READ` | MbedTLS handshake |
| 0x0079D7 | `MbedTLS compile time version = %s, runtime version = %s` | MbedTLS version |
| 0x0093C4 | `gg.xp.tls.mbedtls` | MbedTLS log tag |

### 4.5 CoAP Strings

| Offset | String | Significance |
|--------|--------|--------------|
| 0x00342F | `gg.xp.coap.message` | Log tag |
| 0x009C2F | `gg.xp.coap.blockwise` | Log tag |
| 0x009F6B | `gg.xp.coap.endpoint` | Log tag |
| 0x003E3D | `GG_CoapMessage_CreateFromDatagram` | Message parser |
| 0x004CA7 | `GG_CoapEndpoint_PutData` | Data ingress |
| 0x004C87 | `GG_CoapEndpoint_EnqueueResponse` | Response queue |
| 0x004F79 | `GG_CoapRequestContext_TryToSend` | Request sender |
| 0x008FF2 | `GG_CoapEndpoint_SendResponse` | Response sender |
| 0x00892A | `unsupported CoAP version %u` | Version check |

### 4.6 IPv4 Framing Strings

| Offset | String | Significance |
|--------|--------|--------------|
| 0x0056DD | `creating ipv4 frame assembler - ip_mtu=%d` | Frame assembler |
| 0x00877A | `creating ipv4 frame serializer` | Frame serializer |
| 0x004EA1 | `GG_Ipv4FrameAssembler_Feed` | Assembler feed |
| 0x008820 | `GG_Ipv4FrameSerializer_SerializeFrame` | Serializer |
| 0x00A38F | `GG_Ipv4FrameAssembler_DecompressAndEmitPacket` | Decompressor |

### 4.7 UUID Search Results

| UUID | Found | Location |
|------|-------|----------|
| `ABBAFF00-...` | ❌ NO | Not in native code |
| `ABBAFF01-...` | ❌ NO | Not in native code |
| `ABBAFF02-...` | ❌ NO | Not in native code |
| `AC2F0045-...` | ❌ NO | Not in native code |
| `AC2F0145-...` | ❌ NO | Not in native code |
| `AC2F2645-...` | ❌ NO | Not in native code |

**Conclusion:** UUIDs are **not hardcoded** in `libxp.so`. They are passed from the Java layer through JNI parameters. This confirms the native library is transport-agnostic — the Java layer provides the GATT characteristic UUIDs.

---

## 5. ABBAFF01 Write Path

### 5.1 Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│  Native GoldenGate Stack                                    │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│  │ DTLS Layer  │───→│ CoAP Layer  │───→│ IPv4 Framer │     │
│  └─────────────┘    └─────────────┘    └──────┬──────┘     │
│                                                 │            │
│  ┌──────────────────────────────────────────────┘            │
│  │ GG_GattlinkGenericClient                                   │
│  │ ┌─────────────────────────────────────────────┐            │
│  │ │ GG_GattlinkProtocol                          │            │
│  │ │  ┌──────────────┐   ┌──────────────────┐     │            │
│  │ │  │ State Machine│──→│ Frame Builder    │     │            │
│  │ │  │ INIT → READY │   │ PrepareNextPacket│     │            │
│  │ │  └──────────────┘   └────────┬─────────┘     │            │
│  │ │                               │               │            │
│  │ │  ┌────────────────────────────┘               │            │
│  │ │  │ SendNextPackets → SendRawData              │            │
│  │ │  └─────────────────────────────────────┐      │            │
│  │ └────────────────────────────────────────┘      │            │
│  │                                                 │            │
│  │  TxSink (JNI callback)                         │            │
│  │  └─────────────────────────────────────────────┘            │
└─────────────────────────────────────────────────────────────┘
                              ↓ JNI callback
Java: TxSink.putData(byte[])
                              ↓
Java: RemoteGattlinkNodeDataSender
                              ↓
Java: GattCharacteristicWriter.write(ABBAFF01, data)
                              ↓ BLE
                    Inspire 3: ABBAFF01
```

### 5.2 Frame Construction Functions (from strings)

| Function | Role |
|----------|------|
| `GG_GattlinkProtocol_PrepareNextPacket` | Assembles outgoing frame |
| `GG_GattlinkProtocol_SendNextPackets` | Dispatches TX frames |
| `GG_GattlinkGenericClient_SendRawData` | Sends to transport sink |
| `GG_GattlinkGenericClient_GetOutgoingData` | Retrieves data for TX |

### 5.3 Hardcoded Header Values (from machine code analysis)

| Instruction | Value | Meaning | Occurrences |
|-------------|-------|---------|-------------|
| `mov w2, #0x80` | `1000 0000` | Control, no ACK, PSN=0 | **10** |
| `mov w1, #0x80` | `1000 0000` | Control, no ACK, PSN=0 | **4** |
| `mov w2, #0xC0` | `1100 0000` | Control + ACK, PSN=0 | 1 |
| `mov w1, #0xC0` | `1100 0000` | Control + ACK, PSN=0 | 2 |
| `mov w2, #0x40` | `0100 0000` | Data + ACK, PSN=0 | 9 |
| `mov w1, #0x40` | `0100 0000` | Data + ACK, PSN=0 | 6 |

**Key Finding:** `0x80` (control packet, no ACK, PSN=0) appears **14 times total**. This is the most likely candidate for the **reset/init packet header**.

---

## 6. ABBAFF02 Receive Path

### 6.1 Data Flow

```
Inspire 3: ABBAFF02 NOTIFY
    ↓ BLE
Java: BluetoothGattCallback.onCharacteristicChanged(ABBAFF02, value)
    ↓
Java: GattClientCharacteristicChangeListener (ABBAFF02)
    ↓
Java: RxSource.receiveData(byte[])
    ↓ JNI callback
Native: GG_GattlinkGenericClient_NotifyIncomingDataAvailable
    ↓
Native: GG_GattlinkProtocol_ConsumeIncomingData
    ↓
Native: GG_GattlinkProtocol_HandleIncomingRawData
    ↓
Native: ┌────────────────────────────────────────┐
        │ HandleControlPacket (reset/ack)        │
        │ HandleDataPacket (payload)             │
        └────────────────────────────────────────┘
```

### 6.2 RX Processing Functions

| Function | Role |
|----------|------|
| `GG_GattlinkProtocol_ConsumeIncomingData` | Main RX entry |
| `GG_GattlinkProtocol_HandleIncomingRawData` | Raw data parser |
| `GG_GattlinkProtocol_HandleControlPacket` | Control packet handler |
| `GG_GattlinkProtocol_HandleDataPacket` | Data packet handler |
| `GG_GattlinkProtocol_GetIncomingData` | Data extraction |

### 6.3 ACK Processing Evidence

From strings:
- `Ack: PSN=%d` — ACK packet format
- `Received Ack PSN: %d for %d byte(s), Next expected Ack PSN: %d` — ACK tracking
- `Catching up next_data_sn from %d to %d due to cumulative ACK` — Sequence catchup
- `ACK timer fired` — Retransmission timer
- `Delaying retransmission to prioritize ACK` — ACK prioritization
- `Ignoring retransmitted Ack PSN: %d` — Duplicate detection

---

## 7. First Frame Analysis

### 7.1 Initialization Trigger

The first Gattlink frame is **NOT sent at BLE connection time**. It is sent when:

```
Java: Stack.start()
    ↓
Native: GG_Stack_Start
    ↓
Native: "starting gattlink session"
    ↓
Native: [state machine transitions from INIT]
    ↓
Native: FIRST TX FRAME
```

**This explains our experiment result:** We only connected at the BLE level and enabled notifications. We never initialized the GoldenGate `Stack` and called `Stack.start()`, so the native Gattlink state machine never began.

### 7.2 State Machine (from strings)

```
INIT ──→ RESET sent ──→ RESET COMPLETE received ──→ READY
 │          │                      │                    │
 │          │                      │                    └── Normal data TX/RX
 │          │                      │
 │          │                      └── "resetting session"
 │          │
 │          └── "GG_GattlinkProtocol_SendResetPacket"
 │
 └── "ignoring reset, we're already in the INIT state"
      "Not in READY state (%d), skipping packet transmission"
```

### 7.3 Determinism Assessment

| Aspect | Assessment |
|--------|------------|
| Reset packet header | **LIKELY STATIC** — `0x80` appears 14 times as immediate constant |
| Sequence numbers | **DYNAMIC** — initialized to 0, incremented per-packet |
| ACK numbers | **DYNAMIC** — depends on received packets |
| Payload | **CONTEXT-DEPENDENT** — may carry session parameters |
| Timestamps | Unknown |
| Cryptographic material | **ABSENT at Gattlink layer** — DTLS handles crypto above |

### 7.4 First Frame Hypothesis

Based on evidence:

```
Byte 0: 0x80 = Control packet, no ACK, PSN=0
        (isControl=1, hasAck=0, PSN=0)

Byte 1+: [Control packet type?] [Optional parameters?]
```

The Gattlink packet parser (`GattlinkPacket.parse()`) treats control packets as:
- `size` = total length
- `isControl` = true
- `hasAck` = from bit 6
- `ackPsn` = PSN if hasAck
- `dataPsn` = null (always null for control packets)
- `firstByte` = raw byte 0

But the **control packet type** (reset vs reset-complete vs other) is NOT encoded in the `firstByte` according to the Java parser. It must be in **subsequent bytes** or in a **separate header field**.

**UNKNOWN:** The exact control packet type encoding and any additional initialization bytes.

---

## 8. DTLS Relationship

### 8.1 Library Architecture

```
┌─────────────────────────────────────────┐
│  libxp.so (GoldenGate)                  │
│                                         │
│  ┌─────────────┐                        │
│  │  CoAP       │ ← Application layer    │
│  │  Endpoint   │                        │
│  └──────┬──────┘                        │
│         │                               │
│  ┌──────┴──────┐                        │
│  │  DTLS       │ ← Encryption layer     │
│  │  (MbedTLS)  │   TLS-PSK ciphers      │
│  └──────┬──────┘                        │
│         │                               │
│  ┌──────┴──────┐                        │
│  │  IPv4 Frame │ ← Compression/Framing  │
│  │  Assembler  │                        │
│  └──────┬──────┘                        │
│         │                               │
│  ┌──────┴──────────────┐                │
│  │  Gattlink Protocol  │ ← Transport    │
│  │  (ABBAFF01/ABBAFF02)│                │
│  └─────────────────────┘                │
│                                         │
└─────────────────────────────────────────┘
```

### 8.2 DTLS Evidence

- **Library:** MbedTLS (confirmed by version strings)
- **Version:** DTLSv1.2, TLSv1.3 mentioned
- **Cipher suites:** 14 PSK-based suites identified
- **Key exchange:** TLS-PSK (Pre-Shared Key)
- **PSK resolution:** `GG_TlsKeyResolver_ResolveKey`

### 8.3 Separation

**All layers are in `libxp.so`** — no separate DTLS/CoAP libraries. The GoldenGate library is a monolithic stack.

---

## 9. Confidence Summary

| Claim | Confidence | Evidence |
|-------|------------|----------|
| `libxp.so` = GoldenGate | **CONFIRMED** | `System.loadLibrary("xp")`, build path string |
| Gattlink state machine exists | **CONFIRMED** | Function names and log strings |
| INIT → READY transition | **CONFIRMED** | `"ignoring reset...INIT state"`, `"Not in READY state"` |
| Reset packet header = `0x80` | **HIGH** | 14 immediate constant occurrences |
| Frame has control/data distinction | **CONFIRMED** | Java `GattlinkPacket.parse()` + native strings |
| PSN/ACK sequence tracking | **CONFIRMED** | Multiple sequence-related strings |
| MbedTLS DTLS-PSK | **CONFIRMED** | Cipher suite strings, MbedTLS version string |
| CoAP over DTLS | **CONFIRMED** | CoAP function names + DTLS integration strings |
| IPv4 framing | **CONFIRMED** | `Ipv4FrameAssembler`, `Ipv4FrameSerializer` |
| ABBAFF01 write path | **HIGH** | `TxSink` → `SendRawData` chain |
| Exact reset packet bytes | **UNKNOWN** | Stripped binary; control type encoding unclear |
| First frame is fully static | **LIKELY** | `0x80` is hardcoded; no random/session evidence in Gattlink layer |
| UUIDs hardcoded in native | **CONFIRMED ABSENT** | Searched binary; not found |

---

## 10. Static Analysis Limitation

### What We Know

✅ The library, architecture, state machine, function names, cipher suites, and protocol layers.

✅ The reset packet **probably** starts with `0x80`.

✅ The initialization sequence is triggered by `Stack.start()`, not BLE connection.

### What We Cannot Determine

❌ The exact control packet type encoding (what byte follows `0x80` for RESET vs RESET_COMPLETE).

❌ Whether the reset packet carries additional payload (session parameters, MTU, window sizes).

❌ The exact sequence of packets exchanged during handshake.

❌ The DTLS PSK identity and key derivation.

### Why

The binary is **stripped** — `.symtab` is absent and function boundaries are not recoverable from `.text` alone. While `.rodata` strings expose function names, the actual instruction sequences implementing frame construction are in `.text` without symbols. Cross-referencing strings to code via ADRP patterns is possible but does not reveal the complete byte-level construction algorithm.

### Recommendation

To determine the exact initialization sequence:

1. **BLE Sniffer** (best option): Capture the genuine Fitbit app ↔ Inspire 3 handshake using a Nordic nRF Sniffer or Ellisys analyzer. This will reveal every byte exchanged on ABBAFF01 and ABBAFF02.

2. **Runtime instrumentation** (advanced): Hook the `TxSink.putData()` and `RxSource.receiveData()` methods in the genuine Fitbit app using Frida or Xposed to log the exact byte arrays passed between Java and native layers.

3. **ARM disassembly** (time-intensive): Full manual disassembly of the Gattlink protocol functions in `.text` to reconstruct the frame construction algorithm. Estimated effort: 40-80 hours.

---

## 11. Files Referenced

| File | Description |
|------|-------------|
| `Decoded/Fitbit-arm64/lib/arm64-v8a/libxp.so` | GoldenGate native library (analyzed) |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/GoldenGate.java` | Java loader |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/io/TxSink.java` | TX sink interface |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/io/RxSource.java` | RX source interface |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/stack/Stack.java` | Stack controller |
| `Decoded/Fitbit-base/sources/com/fitbit/goldengate/bindings/gattlink/GattlinkPacket.java` | Frame parser |

---

*Analysis performed via ELF header parsing, dynamic symbol extraction, .rodata string analysis, and AArch64 instruction pattern matching. No binary modification performed.*
