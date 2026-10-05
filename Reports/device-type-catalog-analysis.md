# Fitbit Android App – Device-Type Catalogue Analysis

**Scope:** Complete runtime path from the network device-type catalogue through JSON parsing, `ywe` construction, local caching, and BLE device-identifier matching.  
**Method:** Static analysis of JADX-decompiled `Fitbit-base.apk`.  
**Constraint:** No authentication secrets extracted; no cryptographic checks bypassed.

---

## 1. Executive Summary

**Classification: CONFIRMED**

Fitbit's Android app does **not** ship with a hard-coded device-type catalogue for BLE discovery. Instead, it downloads a public JSON catalogue from:

- **Endpoint:** `GET 1.1/devices/types.json`
- **Retrofit service:** `com.google.android.apps.fitbit.app.datalayer.devicetype.impl.remote.service.DeviceTypeNetworkService`
- **File:** `Decoded/Fitbit-base/sources/com/google/android/apps/fitbit/app/datalayer/devicetype/impl/remote/service/DeviceTypeNetworkService.java`
- **Method:** `getDeviceTypes(@HeaderMap Map<String, String>, bzjc<? super DeviceTypeResponse>)` (line 12)

The response is parsed into `DeviceTypeResponseModel` (Moshi), mapped to `ywe` by `ywr`, cached in a Room database (`device_types` table), wrapped as `pih` by `ahsy`, and used by `qfr` to match the BLE advertisement byte in the `0000180A` service data.

For newer devices such as Inspire 3, the catalogue entry is expected to come from the server. **No Inspire 3 / FB515 identifiers were found in the APK** (see §6).

---

## 2. Runtime Path Overview

**Classification: CONFIRMED**

```
Network
  DeviceTypeNetworkService.getDeviceTypes("1.1/devices/types.json")
  → Retrofit/Moshi → DeviceTypeResponse { List<DeviceTypeResponseModel> }

Parsing
  ywr.a(bzjc) maps each DeviceTypeResponseModel → ywe

Persistence
  ywo repository writes List<ywe> → xod (Room entity) in device_types table
  Cache TTL: 30 minutes (hard-coded in ywo constructor)

BLE matching
  ahsy(ywe) implements pih
  pih.c() = int[] { ywe.a }
  qfr case 10 matches iArrC[0] against 0000180A service-data byte 0
```

---

## 3. JSON Fields and Their Exact Property Names

**Classification: CONFIRMED**

`DeviceTypeResponseModelJsonAdapter` declares the exact JSON keys in its Moshi `options` set.

**File:** `Decoded/Fitbit-base/sources/com/google/android/apps/fitbit/app/datalayer/devicetype/impl/remote/service/DeviceTypeResponseModelJsonAdapter.java`  
**Method:** constructor, `this.options = bsmv.y(...)` (line 33)

| # | JSON property name | JSON adapter case index | Maps to field |
|---|--------------------|------------------------|---------------|
| 1 | `productIds` | 0 | `DeviceTypeResponseModel.a` (List<Integer>) |
| 2 | `name` | 1 | `DeviceTypeResponseModel.b` (String) |
| 3 | `assetsBaseUrl` | 2 | `DeviceTypeResponseModel.c` (String) |
| 4 | `assetBundle` | 3 | `DeviceTypeResponseModel.d` (String) |
| 5 | `assetsToken` | 4 | `DeviceTypeResponseModel.e` (String) |
| 6 | `priority` | 5 | `DeviceTypeResponseModel.f` (Integer) |
| 7 | `supportUrl` | 6 | `DeviceTypeResponseModel.g` (String) |
| 8 | `properties` | 7 | `DeviceTypeResponseModel.h` (List<String>) |
| 9 | `authType` | 8 | `DeviceTypeResponseModel.i` (String) |
| 10 | `availability` | 9 | `DeviceTypeResponseModel.j` (String) |
| 11 | `description` | 10 | `DeviceTypeResponseModel.k` (String) |
| 12 | `deviceCtor` | 11 | `DeviceTypeResponseModel.l` (String) |
| 13 | `deviceEdition` | 12 | `DeviceTypeResponseModel.m` (String) |
| 14 | `maxWifiAccessPoints` | 13 | `DeviceTypeResponseModel.n` (Integer) |
| 15 | `introNudgeUuid` | 14 | `DeviceTypeResponseModel.o` (String) |
| 16 | `wifiFwupLowBatteryThreshold` | 15 | `DeviceTypeResponseModel.p` (Integer) |
| 17 | `wifiSetupLowBatteryThreshold` | 16 | `DeviceTypeResponseModel.q` (Integer) |
| 18 | `wifiSupported` | 17 | `DeviceTypeResponseModel.r` (Boolean) |
| 19 | `displayName` | 18 | `DeviceTypeResponseModel.s` (String) |
| 20 | `hasMusicControl` | 19 | `DeviceTypeResponseModel.t` (Boolean) |
| 21 | `hasMegadumpFwUpdate` | 20 | `DeviceTypeResponseModel.u` (Boolean) |
| 22 | `hasWifiManagement` | 21 | `DeviceTypeResponseModel.v` (Boolean) |
| 23 | `isMotionBit` | 22 | `DeviceTypeResponseModel.w` (Boolean) |
| 24 | `isScale` | 23 | `DeviceTypeResponseModel.x` (Boolean) |
| 25 | `isGallery` | 24 | `DeviceTypeResponseModel.y` (Boolean) |
| 26 | `isChildDevice` | 25 | `DeviceTypeResponseModel.z` (Boolean) |
| 27 | `requiresBondDisconnect` | 26 | `DeviceTypeResponseModel.A` (Boolean) |
| 28 | `hasLiveData` | 27 | `DeviceTypeResponseModel.B` (Boolean) |
| 29 | `bluetoothPairingMethod` | 28 | `DeviceTypeResponseModel.C` (String) |
| 30 | `genus` | 29 | `DeviceTypeResponseModel.D` (String) |
| 31 | `hasBluetooth` | 30 | `DeviceTypeResponseModel.E` (Boolean) |
| 32 | `hasMicrophone` | 31 | `DeviceTypeResponseModel.F` (Boolean) |
| 33 | `hasSpeaker` | 32 | `DeviceTypeResponseModel.G` (Boolean) |
| 34 | `batteryLevelReporting` | 33 | `DeviceTypeResponseModel.H` (Boolean) |
| 35 | `deviceOs` | 34 | `DeviceTypeResponseModel.I` (String) |
| 36 | `compatibleDevices` | 35 | `DeviceTypeResponseModel.J` (List<Integer>) |
| 37 | `phylum` | 36 | `DeviceTypeResponseModel.K` (String) |
| 38 | `minimumRSSI` | 37 | `DeviceTypeResponseModel.L` (Integer) |
| 39 | `whiteLabel` | 38 | `DeviceTypeResponseModel.M` (Boolean) |
| 40 | `maxAccessPoints` | 39 | `DeviceTypeResponseModel.N` (Integer) |
| 41 | `provisioningType` | 40 | `DeviceTypeResponseModel.O` (String) |

**Source constructor annotation (CONFIRMED):**
- **File:** `Decoded/Fitbit-base/sources/com/google/android/apps/fitbit/app/datalayer/devicetype/impl/remote/service/DeviceTypeResponseModel.java`
- **Method:** `DeviceTypeResponseModel(...)` (line 56)

---

## 4. Mapping `DeviceTypeResponseModel` → `ywe`

**Classification: CONFIRMED**

The mapping is performed by `ywr.a(bzjc)`.

**File:** `Decoded/Fitbit-base/sources/defpackage/ywr.java`  
**Method:** `public final Object a(bzjc bzjcVar)` (line 122)

| ywe field | Type | Derived from JSON property | Derivation / notes |
|-----------|------|---------------------------|--------------------|
| `ywe.a` | `int` | `productIds` | First integer in `productIds`; special overrides: `"Aria"` → `-1`, `"MobileTrack"` → `51`, otherwise `0` if list/null empty. |
| `ywe.b` | `String` | `name` | Direct. |
| `ywe.c` | `String` | `assetsBaseUrl` | Direct. |
| `ywe.d` | `String` | `assetBundle` | Direct. |
| `ywe.e` | `String` | `assetsToken` | Direct. |
| `ywe.f` | `Integer` | `priority` | Direct. |
| `ywe.g` | `String` | `supportUrl` | Direct. |
| `ywe.h` | `List<String>` | `properties` | Direct. |
| `ywe.i` | `String` | `authType` | Direct. |
| `ywe.j` | `String` | `availability` | Direct. |
| `ywe.k` | `String` | `description` | Direct. |
| `ywe.l` | `String` | `deviceCtor` | Direct. |
| `ywe.m` | `String` | `deviceEdition` | Direct. |
| `ywe.n` | `Integer` | `maxWifiAccessPoints` | Direct. |
| `ywe.o` | `String` | `introNudgeUuid` | Direct. |
| `ywe.p` | `Integer` | `wifiFwupLowBatteryThreshold` | Direct. |
| `ywe.q` | `Integer` | `wifiSetupLowBatteryThreshold` | Direct. |
| `ywe.r` | `boolean` | `wifiSupported` | Direct, default `false`. |
| `ywe.s` | `String` | `displayName` | Direct; spaces replaced with non-breaking space (`bzmh.aE`). |
| `ywe.t` | `boolean` | `hasMusicControl` | Derived from `properties` containing `"MUSIC_CONTROL"`. |
| `ywe.u` | `boolean` | `hasMegadumpFwUpdate` | Derived from `properties` containing `"MEGADUMP_FWUP"`. |
| `ywe.v` | `boolean` | `hasWifiManagement` | Derived from `properties` containing `"SUPPORTS_WIFI_MGMT"`. |
| `ywe.w` | `boolean` | `isMotionBit` | Derived from `properties` containing `"MOBILETRACK"`. |
| `ywe.x` | `boolean` | `isScale` | Derived from `properties` containing `"SCALE"`. |
| `ywe.y` | `boolean` | `isGallery` | Derived from `properties`? (inferred from `zContains7`) |
| `ywe.z` | `boolean` | `isChildDevice` | Derived from `properties` containing `"TRIGGER_CHILD_PAIRING_FLOW"`. |
| `ywe.A` | `boolean` | `requiresBondDisconnect` | Derived from `properties` containing `"BOND_DISCONNECT"`. |
| `ywe.B` | `boolean` | `hasLiveData` | Direct. |
| `ywe.C` | `PairingMethod` | `bluetoothPairingMethod` | Mapped: `SHOW_SECRET`, `TAP`, `ALERT`, `NEAREST`. |
| `ywe.D` | `String` | `genus` | Direct. |
| `ywe.E` | `boolean` | `hasBluetooth` | Derived from `properties` containing `"BLUETOOTH"`. |
| `ywe.F` | `DeviceCipher` | `authType` | Mapped: `"AES"` → `AES`, `"XTEA"` → `XTEA`; default `AES`. |
| `ywe.G` | `boolean` | `hasMicrophone` | Derived from `properties` containing `"MICROPHONE"`. |
| `ywe.H` | `boolean` | `hasSpeaker` | Derived from `properties` containing `"SPEAKER"`. |
| `ywe.I` | `boolean` | `batteryLevelReporting` | Direct (`deviceTypeResponseModel.H`). |
| `ywe.J` | `String` | `deviceOs` | Direct. |
| `ywe.K` | `List<Integer>` | `compatibleDevices` | Direct. |
| `ywe.L` | `ProvisioningType` | `provisioningType` | Mapped: `FACTORY`/`INFIELD`. |
| `ywe.M` | `DeviceTypePhylum` | `phylum` | Mapped: `"scale"`/`"tracker"`/`"smartwatch"`. |
| `ywe.N` | `Integer` | `minimumRSSI` | Direct. |
| `ywe.O` | `boolean` | `whiteLabel` | Direct. |
| `ywe.P` | `Integer` | `maxAccessPoints` | Direct. |

**Key inference:** The BLE advertisement identifier byte is `ywe.a`, which is the **first value in the server-provided `productIds` array** (with the noted hard-coded overrides for Aria and MobileTrack).

---

## 5. `ywe` Fields Relevant to BLE Discovery / Device Identification

**Classification: CONFIRMED**

| ywe field | Relevance to BLE discovery |
|-----------|---------------------------|
| `a` (int) | **Primary identifier byte** – matched against byte 0 of the `0000180A` service data. |
| `b` (String) | **Device name** – fallback match against `BluetoothDevice.getName()`. |
| `E` (hasBluetooth) | If `false`, the device is not expected to advertise over BLE. |
| `C` (PairingMethod) | Determines the pairing UX (`SHOW_SECRET`, `TAP`, `ALERT`, `NEAREST`). |
| `F` (DeviceCipher) | Selects AES or XTEA for the BLE advertisement crypto check. |
| `M` (DeviceTypePhylum) | `TRACKER` / `SMARTWATCH` / `SCALE` – coarse device class. |
| `D` (genus) | Further grouping string used by UI/onboarding. |
| `h` (properties) | Source of many capability flags; indirectly affects bonding and feature detection. |
| `K` (compatibleDevices) | List of compatible device product IDs. |
| `N` (minimumRSSI) | RSSI threshold for discovery. |

**Matching code (CONFIRMED):**
- **File:** `Decoded/Fitbit-base/sources/defpackage/qfr.java`
- **Method:** `invoke()` case 10 (lines 100–141)
- It compares `((pih) next).c()[0]` against `bArrK[0]` of the `0000180A` service data. `pih.c()` is implemented by `ahsy` as `new int[] { ywe.a }`.

---

## 6. Inspire 3 / FB515 / Identifiable Inspire 3 Values

**Classification: UNKNOWN (absence in APK: CONFIRMED)**

- A case-insensitive search across the entire decompiled source tree and resources for `inspire 3`, `inspire3`, `FB515`, `FB-515`, and related model numbers returned **no device-specific hits**.
- The only matches for `"inspire"` / `"Inspired"` were mood-label strings in `R.java` and `strings.xml`.
- Therefore, **the APK does not contain a hard-coded Inspire 3 profile**.

**What this means:**
- If Fitbit's server catalogue `1.1/devices/types.json` includes an Inspire 3 entry, the app will parse and cache it at runtime.
- The expected JSON shape for an Inspire 3 entry would be the same 41-field object described in §3, with `productIds` containing the BLE identifier byte, `name` likely `"Inspire 3"` or a codename, `phylum` likely `"tracker"`, `hasBluetooth`/`BLUETOOTH` true, and `bluetoothPairingMethod` set to the appropriate enum.
- Without a live or cached response, static analysis cannot confirm the exact identifier byte, model number, or feature flags for Inspire 3.

---

## 7. Local Caching: Where and How the JSON Is Stored

**Classification: CONFIRMED**

### 7.1 Cache database
- **File:** `Decoded/Fitbit-base/sources/com/google/android/apps/fitbit/app/datalayer/database/device/DeviceDatabase.java`
- **Abstract class:** `DeviceDatabase extends lbq` (RoomDatabase)
- **Tables:** `device`, `device_types`, `tracker_info` (see `DeviceDatabase_Impl.a()` line 79)

### 7.2 Cache entity
- **File:** `Decoded/Fitbit-base/sources/defpackage/xod.java`
- **Class:** `xod` (Room entity for `device_types` table)
- **Method:** `public final ywe a()` (converts DB row back to `ywe`)

### 7.3 DAO
- **Interface:** `defpackage.xnw`
- **Implementation:** `defpackage.xoc`
- **File:** `Decoded/Fitbit-base/sources/defpackage/xoc.java`

| DAO method | Purpose |
|------------|---------|
| `a(bzjc)` | `DELETE FROM device_types` |
| `b(List, bzjc)` | Delete device types whose `productId` is not in the given list |
| `c(List, bzjc)` | Insert/replace the list of device types |

### 7.4 Repository / cache TTL
- **File:** `Decoded/Fitbit-base/sources/defpackage/ywo.java`
- **Constructor:** `public ywo(ywr, xof, bzjh, bzjh)` (line 23)
- **TTL:** 30 minutes, computed as `Duration.ofSeconds(bzje.G(30, bzpy.MINUTES), ...)`.

### 7.5 Network fetch + write path
- **File:** `Decoded/Fitbit-base/sources/defpackage/ywl.java`
- **Method:** `invokeSuspend` (lines 46–112)
- Steps:
  1. `ywrVar.a(this)` – fetch JSON from `DeviceTypeNetworkService`.
  2. `bzje.u(xofVar.c, new xoe(list, xofVar, null), this)` – write result to DB.

- **Conversion to DB rows:** `xoe.invokeSuspend` (file `Decoded/Fitbit-base/sources/defpackage/xoe.java`, lines 46–101) maps each `ywe` to an `xod` and inserts via `xnx` (case 0 → `xoc.d.i(...)`).

### 7.6 Cache read path
- **File:** `Decoded/Fitbit-base/sources/defpackage/ywo.java`
- **Methods:** `c()` (all device types) and `d(int)` (single device type by productId)
- Both read `SELECT * from device_types ...` and reconstruct `xod` → `ywe`.

---

## 8. Room Entities and DAOs Involved in Caching Device-Type Information

**Classification: CONFIRMED**

### Primary catalogue database (`com.google.android.apps.fitbit.app.datalayer.database.device`)

| Symbol | Role | File |
|--------|------|------|
| `DeviceDatabase` | Abstract `RoomDatabase` | `.../datalayer/database/device/DeviceDatabase.java` |
| `DeviceDatabase_Impl` | Generated impl; creates `device_types` table | `.../datalayer/database/device/DeviceDatabase_Impl.java` |
| `xod` | Room entity representing one `device_types` row | `Decoded/Fitbit-base/sources/defpackage/xod.java` |
| `xnw` | DAO interface | (JADX shows only `defpackage.xnw`; impl is `xoc`) |
| `xoc` | DAO implementation (`xnx` case 0/1/2) | `Decoded/Fitbit-base/sources/defpackage/xoc.java` |
| `xoa` | Room `Insert` SQL binder | `Decoded/Fitbit-base/sources/defpackage/xoa.java` |
| `xob` | Room `Update` SQL binder | `Decoded/Fitbit-base/sources/defpackage/xob.java` |

### Legacy/auxiliary database (`com.fitbit.fbdevicemodel`)

- **File:** `Decoded/Fitbit-base/sources/com/fitbit/fbdevicemodel/DeviceDatabase.java`
- Also contains a `device_types` table, but the modern catalogue flow uses the `datalayer/database/device` database (evidenced by `ywo` using `xoc`, which belongs to that database).

---

## 9. `device_types` SQL Schema (Primary Cache)

**Classification: CONFIRMED**

From `DeviceDatabase_Impl.java`:

```sql
CREATE TABLE IF NOT EXISTS `device_types` (
  `device_name` TEXT NOT NULL,
  `productId` INTEGER NOT NULL,
  `assetBundle` TEXT,
  `assetsBaseUrl` TEXT,
  `assetsToken` TEXT,
  `authType` TEXT,
  `availability` TEXT,
  `compatibleDevices` TEXT NOT NULL,
  `description` TEXT,
  `deviceCipher` TEXT NOT NULL,
  `deviceCtor` TEXT,
  `deviceEdition` TEXT,
  `deviceOs` TEXT,
  `displayIndex` INTEGER NOT NULL,
  `displayName` TEXT,
  `genus` TEXT,
  `hasBluetooth` INTEGER NOT NULL,
  `hasLiveData` INTEGER NOT NULL,
  `hasMegadumpFwUpdate` INTEGER NOT NULL,
  `hasMicrophone` INTEGER NOT NULL,
  `hasMusicControl` INTEGER NOT NULL,
  `hasSpeaker` INTEGER NOT NULL,
  `hasWifiManagement` INTEGER NOT NULL,
  `insertionTime` INTEGER NOT NULL,
  `introNudgeUuid` TEXT,
  `isChildDevice` INTEGER NOT NULL,
  `isGallery` INTEGER NOT NULL,
  `isMotionBit` INTEGER NOT NULL,
  `isScale` INTEGER NOT NULL,
  `maxAccessPoints` INTEGER,
  `maxWifiAccessPoints` INTEGER,
  `minimumRSSI` INTEGER,
  `pairingMethod` TEXT NOT NULL,
  `phylum` TEXT,
  `priority` INTEGER,
  `properties` TEXT,
  `provisioningType` TEXT NOT NULL,
  `reportsBatteryLevel` INTEGER NOT NULL,
  `requiresBondDisconnect` INTEGER NOT NULL,
  `supportUrl` TEXT,
  `whiteLabel` INTEGER NOT NULL,
  `wifiFwupLowBatteryThreshold` INTEGER,
  `wifiSetupLowBatteryThreshold` INTEGER,
  `wifiSupported` INTEGER NOT NULL,
  PRIMARY KEY(`productId`)
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_device_types_productId` ON `device_types` (`productId`);
```

**File:** `Decoded/Fitbit-base/sources/com/google/android/apps/fitbit/app/datalayer/database/device/DeviceDatabase_Impl.java`  
**Lines:** 157–158, `CREATE TABLE` and `CREATE INDEX`.

---

## 10. Fallback / Default Embedded Profiles

**Classification: CONFIRMED ABSENT**

- **No JSON device catalogue** was found in `assets/`, `res/raw/`, `res/xml/`, or `res/values/`.
- **No hard-coded list of `ywe` / `DeviceTypeResponseModel` objects** was found in the decompiled Java sources.
- The only hard-coded device identifiers are the legacy Bluetooth **name filters** in `pgs.java` (`Decoded/Fitbit-base/sources/defpackage/pgs.java`):
  - `"One"`
  - `"Flex"`
  - `"Charge"`
  - `"Charge HR"`
  - `"Force"`
- These are used only as a `ScanFilter` name whitelist for older trackers and are not a substitute for the full device-type catalogue.

---

## 11. Could BlueBit Use the Same Public/Runtime Device-Profile Concept?

**Classification: STRONG EVIDENCE**

Yes, with caveats.

### What is public
- The JSON endpoint shape (`1.1/devices/types.json`) is now known.
- The `ywe`/`DeviceTypePersistentModel` schema is known (41 fields).
- The mapping from `productIds[0]` to BLE identifier byte is known.
- The BLE matching logic uses only: `productId`/`ywe.a`, `name`/`ywe.b`, `hasBluetooth`, `deviceCipher`, and pairing-related fields.

### What is required independently
- BlueBit would need its own copy of the device profiles for the devices it wants to support, because the Fitbit server catalogue is only accessible to authenticated Fitbit app sessions.
- It does **not** need Fitbit's proprietary UI code; the device profile is a plain data object and the matching logic is straightforward.

### What remains tied to Fitbit
- The identifier byte value for each device (e.g., Inspire 3) is assigned by Fitbit in the server catalogue. Without that value, BlueBit cannot recognize the device via the `0000180A` service data.
- The `deviceCipher`/`authType` and the pairing secrets are per-device and per-account; knowing the profile format does not reveal those secrets.

---

## 12. Class / Method / File Reference Index

| Concept | File | Class / Method | Line(s) |
|---------|------|----------------|---------|
| Retrofit endpoint | `.../devicetype/impl/remote/service/DeviceTypeNetworkService.java` | `getDeviceTypes` | 12–14 |
| JSON response wrapper | `.../devicetype/impl/remote/service/DeviceTypeResponse.java` | `DeviceTypeResponse(List)` | 10–13 |
| JSON model | `.../devicetype/impl/remote/service/DeviceTypeResponseModel.java` | constructor | 56 |
| Moshi adapter options | `.../DeviceTypeResponseModelJsonAdapter.java` | constructor | 33 |
| ywe data class | `Decoded/Fitbit-base/sources/defpackage/ywe.java` | constructor | 55 |
| ywr parser | `Decoded/Fitbit-base/sources/defpackage/ywr.java` | `a(bzjc)` | 122 |
| ywo repository | `Decoded/Fitbit-base/sources/defpackage/ywo.java` | constructor / `b()` / `c()` / `d()` | 23, 47, 83, 258 |
| Network+cache write | `Decoded/Fitbit-base/sources/defpackage/ywl.java` | `invokeSuspend` | 46–112 |
| ywe → xod conversion | `Decoded/Fitbit-base/sources/defpackage/xoe.java` | `invokeSuspend` | 46–101 |
| Room entity | `Decoded/Fitbit-base/sources/defpackage/xod.java` | `a()` | 58 |
| DAO impl | `Decoded/Fitbit-base/sources/defpackage/xoc.java` | `a()`, `b()`, `c()` | 27–31 |
| DB read all | `Decoded/Fitbit-base/sources/defpackage/ywo.java` | `c()` | 83–258 |
| DB read one | `Decoded/Fitbit-base/sources/defpackage/ywo.java` | `d(int)` | 258–387 |
| pih interface | `Decoded/Fitbit-base/sources/defpackage/pih.java` | `a()`, `b()`, `c()` | 5–7 |
| pih impl from ywe | `Decoded/Fitbit-base/sources/defpackage/ahsy.java` | `ahsy(ywe)` | 10–27 |
| BLE service-data match | `Decoded/Fitbit-base/sources/defpackage/qfr.java` | `invoke()` case 10 | 100–141 |
| Legacy name filters | `Decoded/Fitbit-base/sources/defpackage/pgs.java` | `a(nqw)` | 38–53 |
| Primary RoomDatabase | `.../datalayer/database/device/DeviceDatabase.java` | abstract class | 8–14 |
| RoomDatabase_Impl schema | `.../datalayer/database/device/DeviceDatabase_Impl.java` | `a()`, `s()` | 79, 103 |
| Legacy RoomDatabase | `.../com/fitbit/fbdevicemodel/DeviceDatabase.java` | abstract class | 10–57 |

---

*Report generated from static analysis only. No APK modifications or runtime instrumentation were performed.*
