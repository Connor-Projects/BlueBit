# Fitbit Pairing Deep Research — Inspire 3 / Charge 6 Onboarding Architecture

**Date:** 2026-09-30  
**Sources:** Decompiled Fitbit APK (`Decoded/Fitbit-base/sources/`), Gadgetbridge issue #504, GoldenGate open-source repo, BlueBit codebase, public internet research  
**Scope:** Research-only. No BlueBit source code modified.

---

## 1. Executive Summary

**What is known with certainty:**

1. The official Fitbit app pairs the Inspire 3 through a BLE → Gattlink → GoldenGate → DTLS → CoAP stack. The DTLS handshake uses a hardcoded **bootstrap PSK** (identity `"BOOTSTRAP"`, 16-byte key in APK).
2. Before DTLS, the app sends CoAP `pair/display SHOW_CODE` to make the watch display a pairing code, then sends `bond/control` with an `operationTimeout`. These are simple protobufs (one enum field, one int32 field respectively).
3. **Android `createBond()` is called AFTER `bond/control` succeeds** — the official app first tells the watch it's authorized, then triggers Android bonding.
4. After bonding and DTLS bootstrap, the app performs **server-mediated onboarding**: the watch sends an opaque encrypted container via `/sync/dump`, the app relays it to Fitbit's backend, and the backend returns an equally opaque encrypted container that the app relays back via `/sync/response`.
5. **The backend container is a pure relay** — MITM evidence confirms the app never decrypts it. The encryption key lives only on the watch and Fitbit's backend. No amount of APK reverse-engineering yields this key.
6. The opaque container format (`protocol 1027`, "proton-upload") has been **fully reversed** by community researcher philldk: little-endian header, AES-128-EAX encryption, FastLZ compression, persistent nonce counter. Only the 128-bit AES key is missing.
7. **First-time offline onboarding is impossible without the backend secret.** Independent confirmation from Gadgetbridge (mmarca-tech), BlueBit, and philldk all converge on the same blocker.
8. **Post-onboarding operation IS possible.** After pairing once via the official app, a `MobileData` PSK (identity `MD-XXXXXXXX-00000000`, 16-byte key) can be extracted from app private storage and reused in third-party apps. Gadgetbridge PR #6273 already implements this path for Charge 6.

**What is unknown:**
- The AES-128-EAX key for the `/sync/dump` container.
- Whether non-root extraction of MobileData PSKs is possible via Android backup.
- Whether the pairing code displayed on the watch participates in the backend key derivation.

**Most promising path forward:** Implement the **post-onboarding MobileData PSK reuse path** in BlueBit. This requires: (1) key extraction script support, (2) PSK import UI, (3) switching the DTLS resolver from bootstrap PSK to MobileData PSK after import. This would immediately enable battery, heart rate, steps, and live activity reads.

---

## 2. APK Findings — Deep Call-Graph Walkthrough

### 2.1 Pairing flow call graph

```
BondCommandsHandler.create(Context, String btAddress)
  └─> peripheralBondStatusChecker.a(btAddress)
      └─> [if NOT already bonded]
          └─> createBond(context, btAddress)
              ├─> peripheralBondRemover.a(context, btAddress)
              │   └─> [removes old bond if present]
              ├─> bondControlResource.enable(btAddress, timeout)
              │   └─> setBondControl(btAddress, timeout)
              │       └─> createRequest(timeout)
              │           └─> new BondControlRequestBuilder(timeout).build()
              │               └─> BondControl.Control.newBuilder().setOperationTimeout(timeout).build()
              │           └─> endpointMapper.map(btAddress).responseFor(request)
              │               └─> [CoAP PUT bond/control via Gattlink/DTLS]
              │       └─> assertResponseSuccess(response)
              │           └─> [throws ResourceResponseException if not 2.xx]
              └─> bondCreator.create(btAddress, timeout)
                  └─> FitbitGattExtKt.getGattConnection(fitbitGatt, btAddress).toSingle()
                      └─> [gets active GATT connection]
                  └─> create(nrqVar, timeout)
                      └─> CreateBondTransactionProvider.provide(nrqVar, timeout, null)
                          └─> [triggers Android BluetoothDevice.createBond()]
```

**Source classes:**
- `BondCommandsHandler.java` (lines 36-55, 146-168)
- `CoapBondControlResource.java` (lines 50-72)
- `BondControlRequestBuilder.java` (lines 10-18)
- `PeripheralBondCreator.java` (lines 126-140)

### 2.2 pair/display call graph

```
PairCommandsHandler.showPairingCodeOnDevice()
  └─> endpointMapper.map(bluetoothAddress)
  └─> new OutgoingRequestBuilder("pair/display", Method.PUT)
  └─> new PairDisplayRequestBuilder(PairCommandsHandlerKt.showCodeCommand).build()
      └─> PairDisplay.Display.newBuilder().setCommand(SHOW_CODE).build()
  └─> endpoint.responseFor(request)
      └─> [CoAP PUT pair/display via Gattlink/DTLS]
  └─> CoapResponseUtils.assertOkFor(response)
```

**Source classes:**
- `PairCommandsHandler.java` (lines 91-121)
- `PairCommandsHandlerKt.java` (lines 12-14)
- `PairDisplayRequestBuilder.java` (lines 10-18)

### 2.3 DTLS PSK resolver chain

```
TlsKeyResolverRegistry (singleton)
  ├─> firstTlsKeyResolver = HelloTlsKeyResolver
  │   ├─> resolveKey(): identity="hello", key={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15}
  │   └─> if no match, delegates to next
  ├─> registered: BootstrapTlsKeyResolver
  │   ├─> resolveKey(): identity="BOOTSTRAP", key={-127,6,84,-29,54,-83,-54,-80,-96,60,96,-9,74,-96,-74,-5}
  │   └─> if no match, delegates to next
  └─> registered at runtime: MobileDataTlsKeyResolver
      ├─> check: isMobileDataTlsKeyId(identity_bytes)?
      │   └─> must start with "MD-" prefix
      ├─> parse: MobileDataTlsKeyId(keyId_hex, expiration_hex)
      └─> lookup: mobileDataKeyProvider.b(keyId)
          └─> [queries app storage for matching PSK]
```

**Source classes:**
- `TlsKeyResolverRegistry.java` (lines 5-28)
- `HelloTlsKeyResolverKt.java` (lines 12-16)
- `BootstrapTlsKeyResolverKt.java` (lines 13-16)
- `MobileDataTlsKeyResolver.java` (lines 15-54)
- `MobileDataTlsKeyIdExtKt.java` (lines 16-24)
- `TlsKeyResolver.java` (lines 23-31)

### 2.4 Key insight: `createBond()` is NOT required for Gattlink

From philldk's comment on Gadgetbridge #504 (2026-09-22):
> "Connecting without a bond: confirmed 0 SMP events, 0 encryption events in a full sync capture — createBond() always fails and isn't needed. The tracker won't start Gattlink until you confirm its GATT cache: read ac2f0145 to get the UUID of the current 'ephemeral write' characteristic (it rotates per session), then write 0x00 into that UUID ('my cache is invalid, send full DB'). The tracker immediately starts Gattlink unprompted."

This means BlueBit's `Inspire3PairingHandler.kt` is fundamentally wrong in its approach — it tries Android bonding when the watch actually just needs Gattlink cache validation.

### 2.5 GoldenGate command handler architecture

`GoldenGateCommandHandler.java` (932 lines) is the master dispatcher. It imports handlers for:
- Bond: `BondCommandsHandler`
- Pair: `PairCommandsHandler`
- Live Data: `LiveDataCommandsHandler`
- Mobile Data: `MobileDataCommandsHandler`
- File Transfer: `FileReceiverCommandHandler`, `FileSenderCommandHandler`
- Notifications: `NotificationDismissCommandHandler`, `NotificationReceiverCommandHandler`
- App Install: `AppInstallCommandsHandler`
- Sync: `BackgroundSyncHandler`, `EventConsumer`
- Generic: `CoapGenericResource`

Each handler registers CoAP resource paths with the `CoapEndpointMapper`.

---

## 3. Protobuf Analysis

### 3.1 `PairDisplay.Display`
**File:** `com.fitbit.goldengate.protobuf.PairDisplay`  
**Message:** `Display`  
**Fields:**
| Field # | Name | Type | Wire encoding |
|---------|------|------|---------------|
| 1 | `command` | enum `Command` | field 1, varint |

**Enum values:**
| Name | Value |
|------|-------|
| `CLEAR_CODE` | 0 |
| `SHOW_CODE` | 1 |

**Wire format example:**
- `SHOW_CODE`: `0x08 0x01` (field 1, varint, value 1)
- `CLEAR_CODE`: `0x08 0x00` (field 1, varint, value 0)

**Builder:** `PairDisplayRequestBuilder.java`  
**Callers:** `PairCommandsHandler.showPairingCodeOnDevice()`, `PairCommandsHandler.clearPairingCodeOnDevice()`

### 3.2 `BondControl.Control`
**File:** `com.fitbit.goldengate.protobuf.BondControl`  
**Message:** `Control`  
**Fields:**
| Field # | Name | Type | Wire encoding |
|---------|------|------|---------------|
| 1 | `operationTimeout` | int32 | field 1, varint |

**Wire format example (timeout=120):**
- `0x08 0x78` (field 1, varint, value 120)

**Builder:** `BondControlRequestBuilder.java`  
**Callers:** `CoapBondControlResource.createRequest(int)`, `CoapBondControlResource.enable()`, `CoapBondControlResource.disable()`

### 3.3 SyncRequest/EventType
**File:** `com.fitbit.goldengate.protobuf.SyncRequestOuterClass`  
**Enum `EventType` values:**
| Name | Value | Purpose |
|------|-------|---------|
| `WEAR_UNSPECIFIED` | 0 | |
| `WEAR_DEBUG` | 1 | |
| `WEAR_HR_ALERT` | 2 | Heart rate alert |
| `WEAR_ONBOARDING` | 3 | **Onboarding trigger** |
| `WEAR_LOCALE_CHANGED` | 4 | |
| `WEAR_TODAY_SYNC_BUTTON` | 5 | Manual sync button |
| `WEAR_EXERCISE_RECOVER_WORKOUT` | 6 | |
| `WEAR_EXERCISE_UPLOAD_WORKOUT_DATA` | 7 | |
| `WEAR_UAS_USER_AWAKE` | 8 | User awake detection |

**Key finding:** `WEAR_ONBOARDING(3)` suggests the watch can trigger onboarding events.

### 3.4 The opaque `/sync/dump` container (`protocol 1027`)

From philldk's full reversal (Gadgetbridge #504, 2026-09-22):

**Header format (little-endian):**
```
Offset  Size  Description
0       4     version = 1027 (u32 LE)
4       1     encryptionInfo = 2 (AES-128-EAX)
5       4     nonce (used as-is)
9       6     wireId (device identifier)
15      1     compressionType = 1 (FastLZ)
16      4     compressionBlockSize (u32 LE)
20      1     flags
21      N     ciphertext (always multiple of 16 bytes)
21+N    16    EAX tag
```

**Encryption:** AES-128-EAX (Bellare-Rogaway-Wagner)
- `N = OMAC_K^0(nonce)` — also seeds CTR counter
- Empty AAD
- `tag = N ⊕ OMAC_K^1("") ⊕ OMAC_K^2(ciphertext)`
- Matches pycryptodome's `AES.MODE_EAX` with 4-byte nonce

**After decryption:** FastLZ block stream (u32-LE length + FastLZ block, repeated), then SLIP-style sections and footer.

**Server behavior:** MITM confirms the sync endpoint uploads the tracker's encrypted blob and gets back another encrypted blob in the same format — pure relay both directions.

**Nonce behavior:** Persistent monotonically increasing counter, increments by 2 per sync session, survives disconnect/reconnect, never resets in normal use.

**Unknown:** The 128-bit AES-EAX key. It lives only on the tracker and Fitbit's backend.

---

## 4. Backend API Findings

### 4.1 HTTP REST endpoints (from BlueBit FitbitPairingApi.kt)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/1/devices/client/tracker/data/validate.json` | POST | Obtain `pairingToken` for device registration |
| `/1/devices/client/tracker/data/pair.json` | POST | Register pairing attempt with server |
| `/1/devices/client/tracker/data/ack.json` | POST | Acknowledge pairing completion |
| `/1/devices/client/tracker/data/sync.json` | POST | Initiate sync/relay opaque dump container |

**Headers used:**
- `Device-Data-Encoding: BASE_64`
- `X-Fitbit-CompanionAPIVersion: 4.28`
- `X-Client-Criticality: CRITICAL`

**Auth:** Fitbit OAuth2 token (user must be logged into official app).

### 4.2 CoAP endpoints (device-side, over DTLS/Gattlink)

| Path | Method | Purpose |
|------|--------|---------|
| `pair/display` | PUT | Show/clear pairing code on watch |
| `bond/control` | PUT | Enable/disable bonding authorization |
| `sync/dump` | POST/PUT | Watch sends opaque encrypted container |
| `sync/response` | POST/PUT | App relays backend response to watch |
| `md/606` | GET | DeviceInfo protobuf |
| `liveactivity` | GET | LiveActivity protobuf |
| `sync/status` | GET | SyncStatus protobuf |
| `sync/config` | GET | SyncConfig protobuf |
| `app/inbox` | various | Notification inbox |

### 4.3 Key finding: server is pure relay for sync

From philldk (MITM evidence):
> "MITM confirms it: the sync endpoint uploads the tracker's encrypted blob and gets back another encrypted blob in the same format — pure relay both directions."

From mmarca-tech (Gadgetbridge):
> "The app does not appear to locally decode or generate that response."

This means:
1. The app does NOT need to understand the sync container format.
2. The backend does NOT expose the encryption key to the app.
3. The only way to generate a valid `/sync/response` is to have the backend do it.
4. There is no client-side computation that could substitute for the backend.

---

## 5. Online Research Summary

| Source | URL | Contribution |
|--------|-----|-------------|
| **Gadgetbridge issue #504** | `https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/504` | **Primary source.** mmarca-tech's PR #6273 for Charge 6. Confirms `/sync/dump` → backend → `/sync/response` is the blocker. Documents MD key extraction scripts. philldk reversed the full megadump container format (AES-128-EAX + FastLZ). Confirmed server is pure relay. |
| **Fitbit golden-gate (open source)** | `https://github.com/Fitbit/golden-gate` | Official GoldenGate framework source. Confirms architecture: BLE → Gattlink → DTLS → CoAP. Android bindings confirmed in `platform/android/goldengate/`. |
| **Fitbit bitgatt (open source)** | `https://github.com/Fitbit/bitgatt` | Official GATT abstraction library. Confirms `FitbitGattExtKt`, `BitGattPeer`, `PeripheralConnectionStatus` classes seen in decompiled APK. |
| **pewpewthespells BLE reverse** | `https://pewpewthespells.com/blog/fitbit_re.html` | Old pre-GoldenGate protocol (Fitbit One). Documents Airlink opcodes including `SetBondMode(0x06)` = "Displays pairing code", `Dump(0x10)`, `ClientAuthStart(0x50)`. Uses LibTomCrypt (AES/XTEA). **Pre-GoldenGate; not directly applicable to Inspire 3.** |
| **2017 RAID paper** | `https://arxiv.org/abs/1706.09165` | Academic security analysis of pre-GoldenGate Fitbit. Hardware attack vector. Not directly applicable to modern DTLS-based protocol. |
| **34c3 talk** | `https://media.ccc.de/v/34c3-8908-doping_your_fitbit` | CCC presentation on Fitbit protocol. Pre-GoldenGate era. |
| **fitbit-fatbat** | `https://github.com/mrquincle/fitbit-fatbat` | Old Python implementation for pre-GoldenGate Fitbit sync. Historical reference only. |
| **fitbit-air-notification-bridge** | `https://github.com/paceaitian/fitbit-air-notification-bridge` | Third-party Fitbit Air bridge. Security docs mention DTLS PSK. |

---

## 6. Third-Party Project Status

### 6.1 Gadgetbridge (FreeYourGadget) — PR #6273

**Status:** ACTIVE DEVELOPMENT by mmarca-tech (Manuel, Tallinn)

**What works:**
- Charge 6 detection and connection through Gattlink
- Mobile-data DTLS using extracted `MD-...` PSK
- Battery from `DeviceInfo` (`md/606`)
- Heart rate from `liveactivity`
- Liveactivity protobuf parsing
- Historical chart samples: steps, distance, calories, HR, elevation, VA minutes, zone minutes
- Pull-to-refresh with timeout handling

**What doesn't work:**
- First-time onboarding (blocked by `/sync/response`)
- Historical full sync/sleep/HRV/SpO2/GPS (blocked by opaque dump path)
- Saved workout decoding

**Key scripts provided:**
- `extract-fitbit-mobile-data-key.ps1` (PowerShell)
- `extract-fitbit-mobile-data-key.sh` (Bash)

**Prerequisites for key extraction:**
- Rooted Android phone
- ADB access
- Official Fitbit app with device already onboarded

**Output format:** `MD-XXXXXXXX-00000000:32hexchars`

### 6.2 BlueBit

**Status:** Experimental/Research

**What works:**
- BLE scan and discovery
- GATT connection and service discovery
- Unfiltered scan mode
- Gattlink notification subscription
- GoldenGate native stack initialization
- DTLS bootstrap handshake
- CoAP pair/display and bond/control (correct protobufs)
- **bond/control returns 4.01 Unauthorized** (expected — no server onboarding)

**What doesn't work:**
- First-time onboarding (same `/sync/response` blocker)
- Inspire3PairingHandler uses wrong approach (Android bonding instead of Gattlink cache validation)
- No MobileData PSK storage or import
- No post-onboarding CoAP resource access

### 6.3 fitbit-air-notification-bridge

**Status:** Third-party bridge for Fitbit Air

**Relevance:** Uses same GoldenGate/DTLS stack. Security docs confirm DTLS PSK authentication.

---

## 7. Credential/PSK Analysis

### 7.1 Hardcoded PSKs in APK

| PSK Name | Identity | Key (hex) | Source File |
|----------|----------|-----------|-------------|
| Hello | `hello` | `00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F` | `HelloTlsKeyResolverKt.java:14` |
| Bootstrap | `BOOTSTRAP` | `81 06 54 E3 36 AD CA B0 A0 3C 60 F7 4A A0 B6 FB` | `BootstrapTlsKeyResolverKt.java:16` |

### 7.2 MobileData PSK

| Attribute | Value |
|-----------|-------|
| Identity format | `MD-` + 8 hex chars (keyId) + `-` + 8 hex chars (expiration) |
| Example | `MD-12345678-00000000` |
| Key length | 16 bytes (128 bits) |
| Key format | 32 hex chars after colon |
| Storage location | Official app private data (`/data/data/com.fitbit.FitbitMobile/...`) |
| Origin | Server-generated during onboarding |
| Per-device | Yes — each device gets its own MD identity + key |
| Per-installation | Yes — different app installs create different keys for same device |

### 7.3 Where credentials are NOT

From philldk's exhaustive analysis:
> "The app's protocol library has exactly three key-resolver implementations, and all three are for the MobileData/BLE channel (protocol 1028), none of them map a wireId to anything. Hooking the actual decryption entry points live during a real sync shows they never fire. The app genuinely never decrypts this format itself."

The sync dump decryption key is NOT:
- In the APK (checked all resolvers)
- In the app's local storage (three resolver implementations checked)
- Derivable from the wireId or MAC address
- Accessible through any internal key-fetch service

### 7.4 Extraction methods

| Method | Requirements | Status |
|--------|-------------|--------|
| ADB + root + extractor script | Rooted phone, official app onboarded | **WORKS** (Gadgetbridge documented) |
| Waydroid emulator | BLE passthrough | **BROKEN** (waydroid/waydroid#155) |
| Android emulator | Google Play, BLE | **BROKEN** (no actual BLE, keys only created on real pairing) |
| Android backup (`adb backup`) | App must have `allowBackup=true` | **UNKNOWN** — needs investigation |
| Google Takeout | Auto Backup to Google Drive | **UNKNOWN** — needs investigation |
| Hardware dump | JTAG/SWD on watch | **UNKNOWN** — not attempted by community |

---

## 8. Hypotheses Tested

### H1: BlueBit's `Inspire3PairingHandler` approach (GATT read → auto-pairing → createBond)
**Result:** INCORRECT. Community evidence (philldk) confirms `createBond()` is not needed for Gattlink. The tracker starts Gattlink after cache validation (read ac2f0145 + write 0x00), not after bonding.

### H2: Sending `bond/control` without server onboarding should work
**Result:** FALSE. `bond/control` returns `4.01 Unauthorized` because the device has no valid pairing authorization state. The server-mediated `/sync/dump` → `/sync/response` exchange is required first.

### H3: The pairing code displayed on the watch is an Android SMP passkey
**Result:** FALSE. The official app never handles `ACTION_PAIRING_REQUEST`, never calls `setPairingConfirmation()`, and never shows a passkey dialog. The code is application-level display only.

### H4: The `/sync/response` can be generated offline from device data
**Result:** FALSE. philldk's exhaustive analysis confirms the app never decrypts the container and no client-side key exists. The server is a pure relay of encrypted data it generates with a device-specific key.

### H5: The official app locally decodes the sync dump
**Result:** FALSE. MITM evidence and live hooking of decryption entry points both confirm the app is a pure relay.

### H6: Replay attacks with captured `/sync/response` work
**Result:** FALSE. mmarca-tech confirmed: "the watch rejects replayed or incomplete bodies with airlink.nak". The response contains session-specific data (nonce increments by 2 per session).

### H7: MobileData PSK can be extracted without root
**Result:** UNKNOWN. Gadgetbridge's mmarca-tech listed this as an open help request. Waydroid failed. Android backup not yet investigated.

---

## 9. Open Questions

1. **Where exactly in app storage are MobileData PSKs stored?** SharedPreferences, SQLite, encrypted keystore? What is the exact file path and key name?
2. **Can Android backup (`adb backup -noapk com.fitbit.FitbitMobile`) extract PSKs without root?** This is the highest-value unknown.
3. **Does the Fitbit app manifest set `android:allowBackup=true`?** If so, backup extraction is trivial.
4. **What is the exact `operationTimeout` value used by the official app for `bond/control`?** BlueBit uses 120 seconds, but the official value may differ.
5. **Does the pairing code displayed on the watch participate in backend key derivation?** If so, capturing the code during official pairing might help.
6. **Can the `TrackerDump` protobuf (`com.fitbit.goldengate.protobuf.TrackerDump`) parse any portion of the opaque container?** The container has a reversed structure (protocol 1027), but protobuf definitions might partially overlap.
7. **What happens during re-pairing of an already-onboarded device?** Does it skip the `/sync/dump` flow and go straight to MobileData PSK?
8. **Is the Inspire 3's firmware extractable?** Firmware update files (OTA) might contain the sync key or reveal key derivation.
9. **Does the `Hello` PSK serve any purpose in production?** It's a trivially weak key (sequential bytes 0-15) and may only be for development/testing.

---

## 10. Recommended Experiments (Prioritized)

### P0 — Immediate, highest impact

**1. Investigate non-root MobileData PSK extraction via Android backup.**
- Check official Fitbit app manifest for `android:allowBackup`.
- Run `adb backup -noapk com.fitbit.FitbitMobile` on a phone with onboarded device.
- Extract `.ab` file and search for `MD-` strings.
- If successful, document extraction path for BlueBit users.
- **Rationale:** Unblocks onboarding for non-root users. Gadgetbridge explicitly listed this as a help request.

**2. Fix BlueBit's Inspire3PairingHandler to use Gattlink cache validation instead of Android bonding.**
- Remove `createBond()` approach.
- Implement: read `ac2f0145` → get ephemeral write UUID → write `0x00`.
- Wait for Gattlink RESET_REQUEST / RESET_COMPLETE.
- Use window size 8 in RESET_COMPLETE.
- **Rationale:** Community evidence proves bonding is unnecessary. This changes BlueBit's approach from wrong to correct.

### P1 — High impact, medium effort

**3. Implement MobileData PSK import UI in BlueBit.**
- Add input field for `MD-...:<key>` format.
- Store imported PSK securely (Android Keystore if possible, else encrypted SharedPreferences).
- Wire `MobileDataTlsKeyResolver` equivalent into BlueBit's DTLS setup.
- **Rationale:** Enables immediate post-onboarding access to battery, HR, steps.

**4. Port Gadgetbridge's MD key extraction scripts to BlueBit.**
- Adapt PowerShell/Bash scripts to work with any Android device.
- Add as a documentation page or helper tool.
- **Rationale:** Complements PSK import UI with a working extraction path.

**5. Re-pair experiment with official app.**
- Onboard Inspire 3 via official app.
- Unpair from official app (keep app data).
- Attempt connection via BlueBit using extracted MD PSK.
- Does the device accept MobileData DTLS without re-onboarding?
- **Rationale:** Determines if re-pairing is possible without server flow.

### P2 — Research value, longer term

**6. Investigate OTA firmware for key extraction.**
- Capture OTA update URL from official app traffic.
- Download firmware image.
- Search firmware binary for AES keys, sync key derivation logic.
- **Rationale:** Firmware may contain the sync encryption key or reveal derivation.

**7. Map all GoldenGate CoAP resources.**
- Systematically catalog all ~70 protobuf classes in `com.fitbit.goldengate.protobuf/`.
- Determine request/response shapes for each.
- **Rationale:** Enables full post-onboarding feature support.

**8. Implement LiveActivity protobuf parser.**
- Use Gadgetbridge's `mobile_data.proto` definitions.
- Parse `liveactivity` CoAP response into structured data.
- **Rationale:** Enables steps, HR, calories, elevation display.

### P3 — Speculative

**9. MITM capture of official app pairing flow.**
- Use mitmproxy + custom CA on rooted phone.
- Capture exact `/sync/dump` request body and `/sync/response` body.
- Compare with philldk's format reversal.
- **Rationale:** Ground-truth the container format and confirm key non-extractability.

**10. Hardware key extraction.**
- Open Inspire 3, locate debug/test points.
- Attempt JTAG/SWD access to firmware.
- **Rationale:** Ultimate fallback if all software paths fail.

---

## Tool Installation Notes

No additional tools were installed during this investigation. The existing decompiled APK at `G:/Dev/BlueBit/Decoded/Fitbit-base/sources/` provided sufficient evidence.

jadx is available at `G:/Dev/BlueBit/Tools/jadx-1.5.6/` but was not needed — decompiled sources were already present.

Python3 is available at `C:\Users\Connor\AppData\Local\Microsoft\WindowsApps\python3.exe` for potential protobuf/scripting work.

---

## Compilation Verification

No BlueBit source code was modified.

```
:app:compileDebugKotlinAndroid  — BUILD SUCCESSFUL (15 tasks up-to-date, ~16s)
:app:compileKotlinDesktop         — BUILD SUCCESSFUL (2 tasks up-to-date, ~15s)
:app:desktopTest                  — BUILD SUCCESSFUL (6 tasks up-to-date, ~1s)
```
