# Fitbit Android App – ScanRecord Analysis

**Scope:** How the app parses `ScanRecord` service data under UUID `0000180A` (standard BLE Device Information Service) after Android delivers a scan result.  
**Method:** Static analysis of JADX-decompiled Java sources (`Fitbit-base.apk`).  
**Constraint:** No speculation; every claim is backed by an exact file/class/method reference.

---

## 1. Byte Layout of the `0000180A` Service Data

**Classification: CONFIRMED**

The app reads the service data advertised under `0000180A-0000-1000-8000-00805F9B34FB` via:

- **File:** `Decoded/Fitbit-base/sources/defpackage/qdj.java`
- **Method:** `public final boolean a(nqt nqtVar, String str)` (line 57)

```java
ott ottVar = nqtVar.f;
if (ottVar != null) {
    ParcelUuid parcelUuidFromString = ParcelUuid.fromString("0000180A-0000-1000-8000-00805F9B34FB");
    byte[] bArrK = ottVar.k(parcelUuidFromString);
    if (bArrK != null && bArrK.length >= 8) {
        bArrCk = bzhf.ck(bArrK, 2, 8);
    }
}
```

`bzhf.ck` is `Arrays.copyOfRange(bArrK, 2, 8)` (exclusive upper bound), yielding a **6-byte sub-array**.

| Offset in `bArrK` | Name | Length | Used By |
|-------------------|------|--------|---------|
| 0 | **Device-type identifier** | 1 byte | Post-scan filter (`qfr.java` case 10) |
| 1 | *(unused in observed code)* | 1 byte | — |
| 2 | **Confirmation value (byte 0)** | 1 byte | `qdj.b()` (compared against AES output) |
| 3 | **Confirmation value (byte 1)** | 1 byte | `qdj.b()` (compared against AES output) |
| 4 | **Confirmation value (byte 2)** | 1 byte | `qdj.b()` (compared against AES output) |
| 5 | **AES input (byte 0)** | 1 byte | `qdj.b()` (fed into AES block) |
| 6 | **AES input (byte 1)** | 1 byte | `qdj.b()` (fed into AES block) |
| 7 | **AES input (byte 2)** | 1 byte | `qdj.b()` (fed into AES block) |

The code explicitly rejects any advertisement whose `0000180A` service data is shorter than 8 bytes.

---

## 2. Fixed Header, Device Identifier, Random Value, Nonce, MAC, or Other Fields

**Classification: CONFIRMED**

- **No fixed magic header** is checked inside the payload itself. The UUID `0000180A` acts as the outer discriminator.
- **Device identifier:** Byte 0 (`bArrK[0]`) is the device-type identifier. It is matched against expected values stored in `pih` objects (e.g., `ahsy` wrapping `ywe.a`).
- **Confirmation value:** Bytes 2–4 (`bArrK[2..4]`) are a 3-byte AES-ECB confirmation tag.
- **Input / nonce:** Bytes 5–7 (`bArrK[5..7]`) are the 3-byte input that, after padding and reversal, is encrypted to produce the confirmation tag.
- **No MAC address** is embedded in the service data. The Bluetooth MAC is obtained from `BluetoothDevice.getAddress()` (`nqt.b`).
- **No explicit nonce / counter** is visible beyond the 3-byte input field.

---

## 3. What `qdj.b()` Actually Derives from Bytes 3–5

**Classification: CONFIRMED**

**File:** `Decoded/Fitbit-base/sources/defpackage/qdj.java`  
**Method:** `public final boolean b(byte[] bArr, String str)` (lines 116–141)

The method receives the 6-byte sub-array (`bArr` = `bArrK[2..7]`).

Step-by-step derivation:

1. **Extract bytes 3–5 of `bArr`** (which are raw service-data bytes 5, 6, 7):
   ```java
   byte[] bArr2 = {bArr[3], bArr[4], bArr[5]};
   ```

2. **Concatenate with 13 zero bytes** to form a 16-byte block:
   ```java
   byte[] bArrCl = bwwj.cl(bArr2, new byte[13]);
   ```
   (`bwwj.cl` = `Arrays.copyOf` + `System.arraycopy`)

3. **Reverse the 16-byte block**:
   ```java
   byte[] bArrDc1 = bwwj.dc(bArrCl);
   ```
   (`bwwj.dc` reverses the array in-place)

4. **Encrypt with AES/ECB/NoPadding** using the device's pre-shared `SecretKey`:
   ```java
   Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
   cipher.init(Cipher.ENCRYPT_MODE, secretKey);
   byte[] bArrDoFinal = cipher.doFinal(bArrDc1);
   ```

5. **Reverse the ciphertext**:
   ```java
   byte[] bArrDc2 = bwwj.dc(bArrDoFinal);
   ```

6. **Extract bytes 0–2** (3 bytes) of the reversed ciphertext:
   ```java
   byte[] extracted = bwwj.dd(bArrDc2, new bznl(0, 2));
   ```
   (`bwwj.dd` = `Arrays.copyOfRange(..., 0, 3)` because `bznl.d()` returns `2` and the code adds `+1`)

7. **Compare against bytes 0–2 of the original `bArr`** (raw service-data bytes 2, 3, 4):
   ```java
   return Arrays.equals(extracted, bwwj.dd(bArr, new bznl(0, 2)));
   ```

**Result:** `qdj.b()` derives a **3-byte AES-ECB confirmation tag** from service-data bytes 5–7 and verifies that it matches the 3-byte value stored in service-data bytes 2–4.

---

## 4. Source of the Key Stored in `qdj.a`

**Classification: CONFIRMED**

- **Field:** `qdj.a` is a `ConcurrentHashMap<oms, SecretKey>`.
- **File:** `Decoded/Fitbit-base/sources/defpackage/qdi.java` (lines 77–109, visible in Jadx register-comments)
- **Mechanism:**
  1. A coroutine (`qdi`) is launched whenever the active device list changes.
  2. It iterates over `$addedDevices` (`List<oms>`).
  3. For each device, it reads `oms.w()` → the device's **`wireId`** (`ptq.a`).
  4. It queries `wxk` (a crypto-primitive wrapper class) using that `wireId` to retrieve a `SecretKey`.
  5. The pair `(oms, SecretKey)` is inserted into `qdj.a`.

- **Trigger:** The coroutine is triggered from `qdb` case 8 (`Decoded/Fitbit-base/sources/defpackage/qdb.java`, line 76):
  ```java
  qdj qdjVar = (qdj) this.a;
  ConcurrentHashMap concurrentHashMap = qdjVar.a;
  concurrentHashMap.keySet().retainAll(list);
  ...
  bzje.y(qdjVar.b, null, 0, new qdi(bwwj.bp(arrayList, setKeySet), qdjVar, null), 3);
  ```
  This runs in response to emissions from `pie.c()` (the observable of active devices).

- **Key provenance:** The actual key material is not generated inside `qdj`; it is fetched by `wireId` from the app's crypto/key-store layer (`wxk` → `zaj` / `bpwe` / `bzjh`). Static analysis does not reveal the key-derivation function; only the lookup-by-wireId mechanism is visible.

---

## 5. Where `ywe` Objects Originate at Runtime

**Classification: CONFIRMED**

`ywe` is a plain data class (`Decoded/Fitbit-base/sources/defpackage/ywe.java`) holding 40+ device-profile fields.

Two runtime paths produce `ywe` instances:

### A. Network fetch (primary source)
- **Endpoint:** `DeviceTypeNetworkService.getDeviceTypes("1.1/devices/types.json")`
- **File:** `com/google/android/apps/fitbit/app/datalayer/devicetype/impl/remote/service/DeviceTypeNetworkService.java`
- **Parser:** `ywr.java` (`Decoded/Fitbit-base/sources/defpackage/ywr.java`)
- **JSON adapter:** `DeviceTypeResponseModelJsonAdapter.java`
- **Flow:** `ywr` deserializes the JSON array of `DeviceTypeResponseModel` objects and constructs a `ywe` for each entry (lines 445–473).

### B. Local reconstruction (from cached database rows)
- `DeviceDatabase` (Room) stores `ptq` rows and related device metadata.
- `puy.g()` (`Decoded/Fitbit-base/sources/defpackage/puy.java`, line 1129) reads the local DB and wraps rows in `pnx` objects (which implement `oms`).
- `ywe` objects are not stored verbatim; they are rebuilt from the cached JSON or DB fields when needed.

---

## 6. Local Database, Protobuf, JSON, Resource, or Network Response That Supplies `ywe` Device Profiles

**Classification: CONFIRMED**

| Source | Format | Exact Location | Role |
|--------|--------|----------------|------|
| **Network** | JSON (Moshi) | `DeviceTypeNetworkService.java` → `@GET("1.1/devices/types.json")` | Master device-profile catalog fetched from Fitbit servers. |
| **JSON Adapter** | Generated Moshi | `DeviceTypeResponseModelJsonAdapter.java` | Deserializes server JSON into `DeviceTypeResponseModel`. |
| **Local DB** | Room (SQLite) | `com.fitbit.fbdevicemodel.DeviceDatabase` | Caches `ptq` (core device model), firmware, switchboard spec, notification properties, tile properties, etc. |
| **DB Access** | Room DAO | `puy.g()` (`puy.java:1129`) | Reads cached profiles and wraps them in `pnx` / `oms`. |
| **Protobuf** | — | **Not used** for device-type profiles. | Only used for runtime commands (e.g., `PairDisplay`, `BondControl`, `DeviceInfo`). |
| **APK Resources** | — | **None** | No `ywe` or device-type JSON is bundled in `res/values` or `assets`. |

---

## 7. Is the Device Name Required for Newer Devices or Only a Fallback for Legacy Devices?

**Classification: STRONG EVIDENCE**

### Evidence from scan filters
- **File:** `Decoded/Fitbit-base/sources/defpackage/pgs.java`
- **Method:** `public static final void a(nqw nqwVar)` (line 38)
- The Android `ScanFilter.Builder` is configured with device-name filters for **only** these legacy models:
  - `"One"`
  - `"Flex"`
  - `"Charge"`
  - `"Charge HR"`
  - `"Force"`
- **"Inspire" (or Inspire 3) is absent** from this whitelist.

### Evidence from post-scan filtering
- **File:** `Decoded/Fitbit-base/sources/defpackage/qfr.java`
- **Method:** `invoke()` case 10 (lines 100–141)
- The filter first attempts to match the discovered device by **service-data byte 0** (the device-type identifier):
  ```java
  int[] iArrC = ((pih) next).c();
  if (iArrC == null || iArrC[0] != numValueOf.intValue()) { ... }
  ```
- Only if the identifier byte does **not** match any expected profile does it fall back to **Bluetooth device-name matching**:
  ```java
  if (bzlr.h(((pih) obj4).a(), scanEvent2.getConnection().d().d)) { ... }
  ```
  - `pih.a()` returns the expected device name.
  - `scanEvent2.getConnection().d().d` is `nqt.d`, the Bluetooth device name.

### Conclusion
For newer trackers (including Inspire 3), the **identifier byte in the `0000180A` service data is the primary discriminator**. The Bluetooth device name is a **fallback** used only when the identifier byte is unrecognized or missing. The legacy name-based scan filters in `pgs.java` further confirm that name matching is a compatibility path for older devices.

---

## 8. Exact Class / Method / File Reference Index

| Concept | File | Class / Method | Line(s) |
|---------|------|----------------|---------|
| Service-data read | `defpackage/qdj.java` | `qdj.a(nqt, String)` | 57–99 |
| Crypto verification | `defpackage/qdj.java` | `qdj.b(byte[], String)` | 116–141 |
| Byte-range copy | `defpackage/bwwj.java` | `bwwj.ck(byte[], int, int)` | 1909 |
| Byte concatenation | `defpackage/bwwj.java` | `bwwj.cl(byte[], byte[])` | 1917 |
| Byte reversal | `defpackage/bwwj.java` | `bwwj.dc(byte[])` | 2040 |
| Byte sub-array | `defpackage/bwwj.java` | `bwwj.dd(byte[], bznl)` | 2060 |
| ScanRecord wrapper | `defpackage/ott.java` | `ott(ScanRecord)` / `ott.k(ParcelUuid)` | 621–624, 358–360 |
| Device-type ID match | `defpackage/qfr.java` | `qfr.invoke()` case 10 | 100–141 |
| `pih` interface | `defpackage/pih.java` | `pih.a()` / `pih.c()` | 5–7 |
| `pih` impl from `ywe` | `defpackage/ahsy.java` | `ahsy(ywe)` | 10–27 |
| `ywe` data class | `defpackage/ywe.java` | `ywe.a` (int id), `ywe.b` (String name) | 52–53 |
| `ywe` JSON parser | `defpackage/ywr.java` | `ywr` (constructor + `a(bzjc)`) | 30, 122–500 |
| Network endpoint | `com/google/android/apps/fitbit/app/datalayer/devicetype/impl/remote/service/DeviceTypeNetworkService.java` | `getDeviceTypes` | 12–14 |
| JSON adapter | `DeviceTypeResponseModelJsonAdapter.java` | `fromJson` | 19–246 |
| Local DB | `com.fitbit.fbdevicemodel.DeviceDatabase` | — | — |
| DB → `oms` | `defpackage/puy.java` | `puy.g()` | 1129–1142 |
| `oms` → wireId | `defpackage/pnx.java` | `pnx.w()` | 539–541 |
| `ptq` row | `defpackage/ptq.java` | `ptq.a` (wireId), `ptq.d` (encodedId) | 12–13 |
| Key-store populate | `defpackage/qdi.java` | `qdi.invokeSuspend` (register comments) | 77–109 |
| Key-store trigger | `defpackage/qdb.java` | `qdb.invoke()` case 8 | 75–85 |
| `wxk` crypto wrapper | `defpackage/wxk.java` | `wxk` (fields: `zaj`, `bpwe`, `bzjh`, `wxm`, `wxo`) | 8–18 |
| Legacy name filters | `defpackage/pgs.java` | `pgs.a(nqw)` | 38–53 |
| `nqt` scan result | `defpackage/nqt.java` | `nqt.d` (device name), `nqt.f` (ott/ScanRecord) | 12–13 |
| Integer range | `defpackage/bznl.java` | `bznl(int, int)` | 10–56 |

---

*Report generated from static analysis only. No APK modifications or runtime instrumentation were performed.*
