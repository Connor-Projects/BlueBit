# BlueBit — Fitbit Inspire 3 Authentication & Connection Protocol Analysis

**Date:** 2026-08-31  
**Source:** Fitbit Android APK (decompiled via JADX)  
**Target Device:** Fitbit Inspire 3 only

---

## 1. Executive Conclusion

### Does the Fitbit app use Android BLE bonding for the Inspire 3?

**NO.** The Fitbit app does **not** rely on standard Android BLE `createBond()` / `BOND_BONDED` for establishing a trusted connection with the Inspire 3. Our bonding experiment confirmed this independently: the Inspire 3 ignores SMP Pairing Requests entirely (`SMP_RSP_TIMEOUT`).

### What mechanism establishes the trusted Fitbit connection?

**GoldenGate** — Fitbit's proprietary protocol stack running over GATT:

```text
BLE GATT Connection
        ↓
Gattlink (reliable framing over GATT characteristics)
        ↓
DTLS-PSK (transport-layer security, NOT Android SMP)
        ↓
CoAP (application protocol)
        ↓
Fitbit resources (sync, notifications, calls, live data, etc.)
```

The authentication is **application-layer DTLS with pre-shared keys**, not Android BLE bonding.

---

## 2. Connection Flow (Reconstructed from APK)

```text
User opens Fitbit app / background sync triggered
        ↓
Bluetooth LE scan for Inspire 3 (advertisement match)
        ↓
GATT connect (unbonded)
        ↓
GATT service discovery
        ↓
GattDatabaseValidator reads ephemeral pointer
        from AC2F0145 (GattCacheService / EphemeralCharacteristicPointer)
        ↓
Write ephemeral confirmation to cache characteristic
        ↓
Establish Gattlink session over ABBAFF00 service
        (WRITE_NO_RESPONSE on ABBAFF01, NOTIFY on ABBAFF02)
        ↓
DTLS handshake over Gattlink transport
        (PSK identity format: MD-XXXXXXXXXX-XXXXXXXX)
        ↓
CoAP endpoint registration
        ↓
Authenticated GoldenGate session active
        ↓
Sync / notifications / live data / calls flow over CoAP
```

---

## 3. Bonding Evidence

### APK Bonding Code Found

| File | Purpose |
|------|---------|
| `BondCommandsHandler.java` | Checks bond state, triggers `createBond()` if needed |
| `PeripheralBondCreator.java` | Wraps Android `createBond()` in a GATT transaction |
| `CreateBondTransactionProvider.java` | Provides the bond creation transaction |

### Key Finding: `authTracker()` returns `false`

```java
// GoldenGateCommandHandler.java
public bxsz<Boolean> authTracker() {
    return bxsz.just(false);   // <-- ALWAYS FALSE
}
```

**CONFIRMED:** The Fitbit app does **not** consider tracker authentication to be Android BLE bonding. The `authTracker()` method is a stub returning `false`, indicating authentication happens elsewhere.

### Bonding Usage Pattern

The bonding code exists but appears to be:
1. **Legacy support** for older Fitbit devices that do use Android bonding
2. **A command users can trigger** (e.g., Settings → Bond Device)
3. **NOT on the critical path** for normal Inspire 3 connection

---

## 4. Authentication Evidence

### GoldenGate Protocol Stack

| Layer | Component | Evidence |
|-------|-----------|----------|
| **Transport** | Gattlink over GATT | `GattlinkService`, `GattlinkPacket`, `GattlinkStackConfig` |
| **Security** | DTLS-PSK | `DtlsSocketNetifGattlink`, `DtlsProtocolStatus`, `TlsKeyResolver` |
| **Application** | CoAP | `CoapEndpoint`, `CoapResponseUtils`, `ResourceHandler` |

### DTLS States

```java
// DtlsProtocolStatus.java
enum TlsProtocolState {
    TLS_STATE_INIT(0),
    TLS_STATE_HANDSHAKE(1),
    TLS_STATE_SESSION(2),
    TLS_STATE_ERROR(3),
    TLS_STATE_UNKNOWN(-1);
}
```

### PSK Identity Format

```kotlin
// MobileDataTlsKeyIdExtKt.kt
private const val DTLS_PSK_IDENTITY_SIZE = 20
private const val DTLS_PSK_IDENTITY_SEPARATOR = '-'

// Example identity: "MD-1234567890-12345678"
// Bytes: 'M'(77), 'D'(68), '-', [8 hex digits], '-', [8 hex digits]
```

### Key Resolver Chain

| Class | Purpose |
|-------|---------|
| `BootstrapTlsKeyResolver` | Resolves initial/bootstrap keys |
| `HelloTlsKeyResolver` | Resolves hello/introduction keys |
| `InMemoryModeTlsKeyResolver` | In-memory key storage |
| `TlsKeyResolverRegistry` | Chains resolvers together |

**Evidence level:** CONFIRMED — The APK contains full DTLS-PSK infrastructure. The exact key derivation/source is not fully visible in static analysis (likely comes from Fitbit cloud API during device setup).

---

## 5. Inspire 3 GATT Mapping

### Service [4] — Gattlink Service ✅ FULLY MAPPED

| Inspire 3 UUID | APK Name | APK UUID | Properties | Direction |
|----------------|----------|----------|------------|-----------|
| `abbaff00-...` | GattlinkService | `ABBAFF00-...` | PRIMARY | — |
| `abbaff01-...` | ReceiveCharacteristic | `ABBAFF01-...` | WRITE_NO_RESPONSE | Phone → Tracker |
| `abbaff02-...` | TransmitCharacteristic | `ABBAFF02-...` | NOTIFY + 0x2902 | Tracker → Phone |

**Function:** Gattlink is Fitbit's reliable transport over BLE. It frames data into packets with sequence numbers, acknowledgements, and windowing — like a lightweight TCP over GATT.

### Service [5] — GattCacheService ✅ PARTIALLY MAPPED

| Inspire 3 UUID | APK Name | APK UUID | Properties | Match |
|----------------|----------|----------|------------|-------|
| `ac2f0045-...` | GattCacheService | `AC2F0045-...` | PRIMARY | ✅ Exact |
| `ac2f0145-...` | EphemeralCharacteristicPointer | `AC2F0145-...` | READ | ✅ Exact |
| `ac2f2645-...` | *(unknown in APK)* | — | WRITE | ❌ No match |

**Function:** The `GattDatabaseValidator` reads the ephemeral pointer characteristic to validate/confirm the tracker's GATT database. The APK defines `EphemeralCharacteristic` as `AC2F0345` which does NOT match the Inspire 3's `AC2F2645`. This may indicate:
- Different firmware version
- Different characteristic for the same purpose
- APK server code vs. client code mismatch

### Service [3] — Unknown Fitbit Custom A

| Inspire 3 UUID | APK Match | Status |
|----------------|-----------|--------|
| `abbafd00-...` | None found | ❌ Unknown |
| `abbafd01-...` | None found | ❌ Unknown |
| `abbafd02-...` | None found | ❌ Unknown |
| `abbafd03-...` | None found | ❌ Unknown |

### Service [6] — Unknown Fitbit Custom D

| Inspire 3 UUID | APK Match | Status |
|----------------|-----------|--------|
| `4eee1c00-...` | None found | ❌ Unknown |
| `4eee1c01-...` | None found | ❌ Unknown |
| `4eee1c02-...` | None found | ❌ Unknown |
| `4eee1c03-...` | None found | ❌ Unknown |
| `4eee1c04-...` | None found | ❌ Unknown |
| `4eee1c05-...` | None found | ❌ Unknown |
| `4eee1c06-...` | None found | ❌ Unknown |
| `4eee1c07-...` | None found | ❌ Unknown |

### Other APK-Defined Services (Server-Side)

| APK Service | UUID | Role |
|-------------|------|------|
| FitbitGattlinkService | `0000FD62-...` | Server-side Gattlink (not in Inspire 3 scan) |
| GattCacheService | `AC2F0045-...` | Server-side cache (matches Inspire 3 Service [5]) |

---

## 6. Notification Subscriptions

### How the Fitbit App Subscribes

The APK **does not** use Android's `setCharacteristicNotification()` directly for the main protocol. Instead:

1. **Gattlink handles its own subscription** through the `TransmitCharacteristicSubscriptionListener`
2. **Data flows through Gattlink framing**, not raw characteristic notifications
3. **Individual CoAP resources** handle specific features

### Notification Pathway Architecture

```text
Android NotificationListenerService
        ↓
onNotificationPosted(StatusBarNotification)
        ↓
ServiceToAppMessageSender (osk)
        ↓
GoldenGate CoAP layer
        ↓
notificationReceiverResourceHandler
        ↓
DTLS-encrypted Gattlink frames
        ↓
WRITE_NO_RESPONSE on ABBAFF01
        ↓
Inspire 3 receives notification data
```

### Notification-Related CoAP Resources

| Handler | Type | Evidence |
|---------|------|----------|
| `notificationReceiverResourceHandler` | Unsolicited small data | `getUnsolicitedDataObservable()` merge |
| `notificationDismissResourceHandler` | Dismiss | Same merge stream |
| `sendSecureAppNotification(byte[])` | Secure app notification | `GoldenGateCommandHandler` |
| `sendUnsecuredAppNotification(byte[])` | Unsecured app notification | `GoldenGateCommandHandler` |

---

## 7. Phone Call Pathway

### Architecture

```text
Android Telephony / PhoneCallReceiver
        ↓
Incoming call intent (incoming_number)
        ↓
Call state processing
        ↓
GoldenGate CoAP layer
        ↓
DTLS-encrypted Gattlink frames
        ↓
Inspire 3 displays call
```

### Key Components Found

| Component | File | Purpose |
|-----------|------|---------|
| `PhoneCallReceiver` | `device/notifications/listener/calls/PhoneCallReceiver.java` | Receives incoming call broadcasts |
| `onwrist_calls_phone_calls` | `R.java` string resource | UI for on-wrist call feature |
| `onwrist_calls_blebond_in_progress_description` | `R.java` string resource | Call setup during bonding |

---

## 8. Device-Specific Branching

### Inspire 3 String References

**No direct `Inspire3` / `Inspire 3` / `inspire_3` code branching found** in the APK source. The decompiled code uses:
- Generic tracker/peripheral abstractions
- Protocol-based capability detection
- GATT database validation to confirm device compatibility

### Implication

The Fitbit app treats the Inspire 3 as a **generic GoldenGate-capable device**. There is no Inspire 3-specific authentication or connection code path. The same GoldenGate stack handles multiple Fitbit device families.

---

## 9. Exact GATT Operations (Confirmed)

### Operation 1: GattDatabaseValidator — Read Ephemeral Pointer

```
READ  AC2F0045/AC2F0145
      (GattCacheService / EphemeralCharacteristicPointer)
      ↓
Return: UUID (16 bytes)
```

**APK class:** `GattDatabaseValidator.readEphemeralPointerCharacteristic()`

### Operation 2: GattDatabaseValidator — Write Ephemeral Confirmation

```
WRITE AC2F0045/<returned-UUID>
      (GattCacheService / EphemeralCharacteristic)
      Payload: [0x00] (1 byte)
      Write type: WRITE_NO_RESPONSE (2)
```

**APK class:** `GattDatabaseValidator.writeEphemeralCharacteristic()`

### Operation 3: Gattlink — Subscribe to Transmit

```
WRITE_DESCRIPTOR  ABBAFF00/ABBAFF02/00002902
      (GattlinkService / TransmitCharacteristic / Client Characteristic Config)
      Value: ENABLE_NOTIFICATION_VALUE (0x0100)
```

**Purpose:** Enable tracker → phone notifications over Gattlink

### Operation 4: Gattlink — Write Data

```
WRITE ABBAFF00/ABBAFF01
      (GattlinkService / ReceiveCharacteristic)
      Write type: WRITE_NO_RESPONSE
      Payload: Gattlink-framed data (DTLS/CoAP packets)
```

---

## 10. Unknowns

| Unknown | Why | Next Step |
|---------|-----|-----------|
| Exact PSK source | Key derivation happens in Fitbit cloud API | Capture HTTPS traffic during device setup |
| Service [3] purpose | No APK match for `abbafd00` service | Test characteristic reads/writes |
| Service [6] purpose | No APK match for `4eee1c00` service | Test characteristic reads/writes |
| DTLS cipher suite | Native code, not visible in Java | Capture BLE traffic with nrf sniffer |
| CoAP resource paths | Native GoldenGate bindings | Capture BLE traffic or dump native symbols |
| `ac2f2645` vs `ac2f0345` | Mismatch between APK and Inspire 3 firmware | Verify actual characteristic behavior |
| Full Gattlink frame format | Partial info from `GattlinkPacket.java` | Capture and decode actual frames |
| Pairing code flow | `showPairingCodeOnDevice()` exists | Test if Inspire 3 requires initial pairing |

---

## 11. Recommended Next BlueBit Experiment

### Experiment: Reproduce GattDatabaseValidator Flow

**Goal:** Perform the first confirmed GATT operations after connection.

**Steps:**

```text
1. Connect GATT to Inspire 3 (unbonded)
2. Discover services
3. READ characteristic AC2F0145 (Service AC2F0045)
   ↓
4. Observe returned UUID
5. WRITE [0x00] to that UUID under service AC2F0045
   ↓
6. Log results
```

**Expected outcomes:**
- **Success:** The read returns a UUID, the write succeeds → confirms database validation protocol
- **Failure:** Read returns error or unexpected data → indicates different protocol version

**Why this is the safest next step:**
- Uses only READ and WRITE operations (no subscriptions)
- No authentication required
- Based on confirmed APK code
- Non-destructive (writing a single zero byte)
- Will tell us immediately if the Inspire 3 expects the documented protocol

### If Successful, Next Experiments:

```text
1. Enable notifications on ABBAFF02 (Gattlink transmit)
2. Write Gattlink framing bytes to ABBAFF01
3. Observe if tracker responds with Gattlink acknowledgements
4. Attempt DTLS handshake over Gattlink
```

---

## Evidence Summary Table

| Claim | Evidence Level | Source |
|-------|---------------|--------|
| Android BLE bonding NOT used | CONFIRMED | `SMP_RSP_TIMEOUT` in system logs + `authTracker() = false` |
| DTLS-PSK over Gattlink | CONFIRMED | `DtlsSocketNetifGattlink`, `DtlsProtocolStatus`, `MobileDataTlsKeyIdExtKt` |
| CoAP application protocol | CONFIRMED | `CoapEndpoint`, `CoapResponseUtils`, resource handlers |
| Gattlink Service = `ABBAFF00` | CONFIRMED | `GattlinkService.java` UUID matches Inspire 3 Service [4] |
| GattCache Service = `AC2F0045` | CONFIRMED | `GattCacheService.java` UUID matches Inspire 3 Service [5] |
| NotificationListenerService used | CONFIRMED | `IndependentNotificationListenerService extends NotificationListenerService` |
| Phone call receiver exists | CONFIRMED | `PhoneCallReceiver.java` |
| GattDatabaseValidator reads `AC2F0145` | CONFIRMED | `GattDatabaseValidator.readEphemeralPointerCharacteristic()` |
| PSK identity format | LIKELY | `MobileDataTlsKeyIdExtKt` pattern matching |
| Exact CoAP resource paths | UNKNOWN | Buried in native GoldenGate bindings |
| Service [3] purpose | UNKNOWN | No APK match |
| Service [6] purpose | UNKNOWN | No APK match |
