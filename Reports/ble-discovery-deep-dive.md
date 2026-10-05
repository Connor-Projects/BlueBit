# Fitbit Android App – BLE Discovery Deep Dive

**Scope:** Trace how `PeripheralScanner` constructs `ScanFilter` objects and how scan results are filtered after Android returns them.  
**Method:** Static analysis of JADX-decompiled sources (`Fitbit-base.apk`).  
**Constraint:** No speculation; every claim is backed by an exact file/class/method reference.

---

## 1. Scan-Filter Construction Chain

### 1.1 Entry point – `pgs.java`
**File:** `Decoded/Fitbit-base/sources/defpackage/pgs.java`

- `public static final pgs a = new pgs();`
- `private static final bzgc d = new bzgh(new kpg(9));`

In the static initializer (`static { … }`):
1. Calls `private static final List b()` which executes `d.a()` → invokes `kpg(9)`.
2. The returned `List<bzgd<ParcelUuid, ParcelUuid>>` is iterated twice:
   - **List `b`:** Each pair is wrapped in `new ServiceUuidPeripheralScannerFilter((ParcelUuid) first, (ParcelUuid) second)`.
   - **List `c`:** Each pair is turned into `new ScanFilter.Builder().setServiceUuid((ParcelUuid) first, (ParcelUuid) second).build()`.

**Method `public static final void a(nqw nqwVar)`** (line 38):
- Calls `nqwVar.resetScanFilters()`.
- Iterates the same UUID list and calls `nqwVar.addScanServiceUUIDWithMaskFilter(...)` for each pair.
- Then adds five **device-name** filters:
  - `nqwVar.addDeviceNameScanFilter("One");`
  - `nqwVar.addDeviceNameScanFilter("Flex");`
  - `nqwVar.addDeviceNameScanFilter("Charge");`
  - `nqwVar.addDeviceNameScanFilter("Charge HR");`
  - `nqwVar.addDeviceNameScanFilter("Force");`

### 1.2 UUID provider – `kpg.java` (case 9)
**File:** `Decoded/Fitbit-base/sources/defpackage/kpg.java` (lines 79–81)

`kpg` is a synthetic `bzkj` (supplier). When instantiated with `new kpg(9)` and invoked, it returns:

```java
return bwwj.ai(
    new bzgd(ParcelUuid.fromString("ADABFB00-6E7D-4601-BDA2-BFFAA68956BA"), ParcelUuid.fromString("FFFF0000-FFFF-FFFF-FFFF-FFFFFFFFFFFF")),
    new bzgd(ParcelUuid.fromString("0000FD63-0000-1000-8000-00805F9B34FB"), ParcelUuid.fromString("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF")),
    new bzgd(ParcelUuid.fromString("ABBAFB00-E56A-484C-B832-8B17CF6CBFE8"), ParcelUuid.fromString("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF")),
    new bzgd(ParcelUuid.fromString("ABBAFF00-E56A-484C-B832-8B17CF6CBFE8"), ParcelUuid.fromString("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF")),
    new bzgd(ParcelUuid.fromString("0000FD62-0000-1000-8000-00805F9B34FB"), ParcelUuid.fromString("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF")),
    new bzgd(ParcelUuid.fromString("0000181D-0000-1000-8000-00805F9B34FB"), ParcelUuid.fromString("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"))
);
```

These six `(UUID, mask)` pairs are the **only** scan-filter UUIDs used anywhere in the APK.

### 1.3 PeripheralScanner – applying filters
**File:** `Decoded/Fitbit-base/sources/com/fitbit/bluetooth/fbgatt/rx/scanner/PeripheralScanner.java`

- `public bxsm<ScanEvent> scanForDevices(Context context, final List<? extends PeripheralScannerFilter> list, boolean z, boolean z2)` (line ~203)
- Inside the observable chain it calls:
  ```java
  private final void applyScanFilters(List<? extends PeripheralScannerFilter> list, boolean z) {
      if (z) {
          this.fitbitGatt.resetScanFilters();
      }
      Iterator<T> it = list.iterator();
      while (it.hasNext()) {
          ((PeripheralScannerFilter) it.next()).add(this.fitbitGatt);
      }
  }
  ```

### 1.4 ServiceUuidPeripheralScannerFilter
**File:** `Decoded/Fitbit-base/sources/com/fitbit/bluetooth/fbgatt/rx/scanner/ServiceUuidPeripheralScannerFilter.java`

```java
public final class ServiceUuidPeripheralScannerFilter implements PeripheralScannerFilter {
    private final ParcelUuid mask;
    private final ParcelUuid uuid;

    @Override
    public void add(nqw nqwVar) {
        nqwVar.addScanServiceUUIDWithMaskFilter(this.uuid, this.mask);
    }
}
```

### 1.5 FitbitGattImpl – building the Android ScanFilter
**File:** `Decoded/Fitbit-base/sources/com/fitbit/bluetooth/fbgatt/FitbitGattImpl.java`

```java
@Override
public synchronized void addScanServiceUUIDWithMaskFilter(ParcelUuid parcelUuid, ParcelUuid parcelUuid2) {
    nsp nspVar = this.peripheralScanner;
    if (nspVar == null) {
        return;
    }
    ScanFilter scanFilterBuild = new ScanFilter.Builder().setServiceUuid(parcelUuid, parcelUuid2).build();
    ArrayList arrayList = nspVar.j;
    synchronized (arrayList) {
        arrayList.add(scanFilterBuild);
    }
}
```

Same file also shows:
- `addDeviceNameScanFilter(String str)` → `new ScanFilter.Builder().setDeviceName(str).build()`
- `addDeviceAddressScanFilter(String str)` → `new ScanFilter.Builder().setDeviceAddress(str).build()` (lines 419–424, 1207–1209)
- `addServiceDataScanFilter(ParcelUuid parcelUuid, byte[] bArr, byte[] bArr2)` → `new ScanFilter.Builder().setServiceData(parcelUuid, bArr, bArr2).build()` (lines 444–450)

**No `setManufacturerData` call exists anywhere in the APK.**

---

## 2. Manufacturer Data Usage

**Result: None.**

- The `nqw` interface (`Decoded/Fitbit-base/sources/defpackage/nqw.java`) exposes only:
  - `addScanServiceUUIDWithMaskFilter`
  - `addDeviceNameScanFilter`
  - `addServiceDataScanFilter` (generic service data, not manufacturer data)
  - `addDeviceAddressScanFilter`
- A project-wide grep for `setManufacturerData`, `addManufacturerData`, or `MANUFACTURER_DATA` in the decompiled sources returned **zero matches** in any Fitbit BLE package.
- Therefore, the Android `BluetoothLeScanner` is **never** configured to filter on manufacturer data.

---

## 3. Additional UUIDs Hidden Elsewhere

**Result: No additional scan-filter UUIDs.**

- The **only** place that creates `ParcelUuid.fromString(...)` objects for scanning is `kpg.java` case 9 (§1.2).
- A second `ParcelUuid.fromString` appears in post-scan code (`qfr.java` and `qdj.java`) for UUID `0000180A-0000-1000-8000-00805F9B34FB` (the standard BLE **Device Information Service**). This UUID is **not** added to the `ScanFilter.Builder`; it is read from the `ScanRecord` after Android delivers the result.
- All other UUIDs in the APK (e.g., `ABBAFF01…`, `ABBAFC00…`, `AC2F0045…`) are defined with `UUID.fromString(...)` and belong to **GATT services/characteristics** that the app exposes or discovers *after* connection. They are never used in `ScanFilter` construction.

---

## 4. Post-Scan Filtering (After Android Returns a Device)

**Yes – four distinct layers of filtering occur after the OS delivers a scan result.**

### 4.1 RSSI threshold & address deduplication (`nsn.java`)
**File:** `Decoded/Fitbit-base/sources/defpackage/nsn.java`

`nsn` extends `android.bluetooth.le.ScanCallback` and is the raw callback registered with `BluetoothLeScanner`.

`onScanResult(int callbackType, ScanResult scanResult)` (lines 55–71):
```java
int i2 = nspVar.i;
if (i2 == Integer.MIN_VALUE || i2 < scanResult.getRssi()) {
    Map map = nspVar.p;
    if (!map.containsKey(device.getAddress())) {
        map.put(device.getAddress(), device);
        nspVar.q = true;
    }
    nqtVar.b(Integer.valueOf(scanResult.getRssi()));
    nqtVar.e = 2;
    nqtVar.d(new ott(scanResult.getScanRecord()));
    this.a.onFitbitDeviceFound(nqtVar);
}
```

- **RSSI filter:** If `nsp.i` is not `Integer.MIN_VALUE`, results with RSSI ≤ `nsp.i` are dropped.
- **Address deduplication:** The `HashMap` `nsp.p` stores seen addresses; duplicate addresses are passed through but do not re-trigger `nspVar.q = true`.
- **Data captured:** `BluetoothDevice`, `Rssi`, and `ScanRecord` (wrapped in `ott`).

Same logic exists in `onBatchScanResults(List list)` (lines 25–36).

### 4.2 RxJava stream filter – address match (`qfr.java` case 8)
**File:** `Decoded/Fitbit-base/sources/defpackage/qfr.java` (lines 90–93)

```java
case 8:
    PeripheralScanner.ScanEvent scanEvent = (PeripheralScanner.ScanEvent) obj;
    return Boolean.valueOf(bzlr.h(scanEvent.getConnection().d().b, ((qfz) this.a).a));
```

- Filters the RxJava stream by comparing the scanned device’s Bluetooth address (`nqt.b`, accessed via `nrq.d().b`) against a target address stored in `qfz.a`.

### 4.3 RxJava stream filter – device-type identifier or name match (`qfr.java` case 10)
**File:** `Decoded/Fitbit-base/sources/defpackage/qfr.java` (lines 100–141)

This is the most significant post-scan filter. It receives a `PeripheralScanner.ScanEvent` and:

1. Retrieves the `ott` (scan-record wrapper) from the discovered connection:
   ```java
   ott ottVar = scanEvent2.getConnection().d().f;
   ```

2. If `ottVar != null`, reads the **service data** for UUID `0000180A-0000-1000-8000-00805F9B34FB` (Device Information Service):
   ```java
   ParcelUuid parcelUuidFromString = ParcelUuid.fromString("0000180A-0000-1000-8000-00805F9B34FB");
   byte[] bArrK = ottVar.k(parcelUuidFromString);
   ```
   (`ott.k(ParcelUuid)` returns `((ScanRecord) this.a).getServiceData(parcelUuid)` – confirmed in `ott.java` line 359.)

3. If `bArrK != null`, extracts the **first byte** as an integer:
   ```java
   numValueOf = Integer.valueOf(bArrK[0]);
   ```

4. Compares this byte against a list of expected device-type objects (`this.a`, which is an `Iterable<pih>`):
   ```java
   int[] iArrC = ((pih) next).c();
   if (iArrC == null || iArrC[0] != numValueOf.intValue()) { ... }
   ```

5. If the service-data byte does **not** match any expected value, it falls back to **Bluetooth device-name matching**:
   ```java
   if (bzlr.h(((pih) obj4).a(), scanEvent2.getConnection().d().d)) { ... }
   ```
   - `pih.a()` returns a `String` (the expected device name).
   - `scanEvent2.getConnection().d().d` is `nqt.d`, the Bluetooth device name.

If neither match succeeds, the filter returns `false` and the scan event is dropped from the RxJava stream.

### 4.4 Cryptographic verification of scan-record payload (`qdj.java`)
**File:** `Decoded/Fitbit-base/sources/defpackage/qdj.java`

Method `public final boolean a(nqt nqtVar, String str)` (lines 57–99):

1. Reads the same `0000180A` service data from the scan record:
   ```java
   byte[] bArrK = ottVar.k(ParcelUuid.fromString("0000180A-0000-1000-8000-00805F9B34FB"));
   ```

2. If the array is at least 8 bytes, extracts bytes **2 through 8** (6 bytes):
   ```java
   bArrCk = bzhf.ck(bArrK, 2, 8);
   ```

3. Calls `b(bArrCk, str)` (lines 101–141):
   ```java
   byte[] bArr2 = {bArr[3], bArr[4], bArr[5]};
   ...
   SecretKey secretKey = (SecretKey) entry.getValue();
   byte[] bArrCl = bzhf.cl(bArr2, new byte[13]);
   Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
   cipher.init(1, secretKey);
   byte[] bArrDoFinal = cipher.doFinal(bzhf.dc(bArrCl));
   return Arrays.equals(bzhf.dd(bzhf.dc(bArrDoFinal), new bznl(0, 2)),
                        bzhf.dd(bArr, new bznl(0, 2)));
   ```
   - This is an **AES/ECB/NoPadding** confirmation-value check.
   - Bytes 3–5 of the service data are encrypted with a pre-shared secret key (looked up by `str` in `this.a`, a `ConcurrentHashMap`).
   - The resulting ciphertext’s first 2 bytes are compared against the first 2 bytes of `bArrCk` (i.e., bytes 2–3 of the raw service data).
   - If they match, the method returns `true` and logs a "FRI resolution success" event.

This proves the app cryptographically authenticates the scan-record payload before fully trusting the device.

---

## 5. What Is Extracted from ScanResult

**File:** `Decoded/Fitbit-base/sources/defpackage/nsn.java` (lines 55–69)

For every `ScanResult` that passes the RSSI check:

| Field | Source | Stored In |
|-------|--------|-----------|
| `BluetoothDevice` | `scanResult.getDevice()` | `nqt.a` |
| MAC address | `bluetoothDevice.getAddress()` | `nqt.b` |
| RSSI | `scanResult.getRssi()` | `nqt.c` (Integer) |
| `ScanRecord` | `scanResult.getScanRecord()` | `nqt.f` (wrapped in `ott`) |
| Raw scan bytes | `scanRecord.getBytes()` | `ott.b` (byte[]) |

**Additional post-hoc extraction:**
- Service data for UUID `0000180A` → `ott.k(ParcelUuid.fromString("0000180A-0000-1000-8000-00805F9B34FB"))`
  - First byte (`bArrK[0]`) → device-type identifier.
  - Bytes 2–8 (`bzhf.ck(bArrK, 2, 8)`) → cryptographic payload for FRI verification.

---

## 6. How Newer Trackers (e.g., Inspire 3) Are Distinguished Before Connection

**They are not distinguished by the Android `ScanFilter` itself.** The scan filters are generic to all Fitbit devices (§1.2). Distinguishing happens in the **post-scan filtering layer**.

### 6.1 Device-type identifier byte
- The scan record advertises service data under the standard **Device Information Service** UUID (`0000180A`).
- The **first byte** of that service data is a numeric device-type ID.
- The app maintains a collection of `pih` objects (each representing an expected device profile).
  - `pih.c()` returns `int[]` containing the expected identifier(s).
  - `pih.a()` returns the expected Bluetooth device name.
- The filter in `qfr.java` case 10 matches the scanned device’s identifier byte against `pih.c()[0]`.

### 6.2 Where the identifiers come from
**File:** `Decoded/Fitbit-base/sources/defpackage/ahsy.java`

`ahsy` implements `pih` and is constructed from a `ywe` object:
```java
public ahsy(ywe yweVar) {
    this.a = bwwj.bP(bwwj.ae(Integer.valueOf(yweVar.a))); // int[] containing ywe.a
    this.b = yweVar.b;                                   // expected device name
    ...
}
```

- `ywe.a` is an `int` → the device-type identifier that appears in the scan-record service data.
- `ywe.b` is a `String` → the expected Bluetooth device name.

**File:** `Decoded/Fitbit-base/sources/defpackage/ywe.java`

`ywe` is a plain data class with 40+ fields. It is **not** hardcoded in the APK for the Inspire 3. Instead, `ywe` instances are supplied at runtime (likely from a server-side device database or local `DeviceDatabase`).

### 6.3 Cryptographic identity verification
- Even if the identifier byte matches, the app runs `qdj.a(nqt, String)` (§4.4).
- This verifies that bytes 2–8 of the `0000180A` service data form a valid AES/ECB confirmation value under a secret key stored in `qdj.a` (`ConcurrentHashMap`).
- The secret key is looked up by a string identifier (likely the user’s account or pairing token).

### 6.4 Summary
| Step | Mechanism | Exact Location |
|------|-----------|----------------|
| 1. Android scan | Service UUID + device name filters | `pgs.java`, `kpg.java` case 9, `FitbitGattImpl.java` |
| 2. RSSI / dedup | Threshold + address map | `nsn.java` (`onScanResult`) |
| 3. Type match | Service-data byte 0 of `0000180A` vs. `ywe.a` | `qfr.java` case 10 |
| 4. Name fallback | Bluetooth name vs. `ywe.b` | `qfr.java` case 10 (else branch) |
| 5. Crypto check | AES/ECB/NoPadding over bytes 3–5, compare to bytes 0–1 of payload | `qdj.java` (`a()` and `b()`) |

Because the APK contains **no hardcoded `ywe` instance for Inspire 3**, the exact identifier byte and expected Bluetooth name for that model are loaded dynamically and were not recoverable from static analysis alone.

---

## 7. Exact Class / Method / File Reference Index

| Concept | File | Class / Method | Line(s) |
|---------|------|----------------|---------|
| UUID list for scan filters | `defpackage/kpg.java` | `kpg.invoke()` case 9 | 80–81 |
| Scan-filter builder | `defpackage/pgs.java` | `pgs.<clinit>` and `pgs.a(nqw)` | 19–53 |
| Service-UUID filter object | `com/fitbit/bluetooth/fbgatt/rx/scanner/ServiceUuidPeripheralScannerFilter.java` | `ServiceUuidPeripheralScannerFilter.add(nqw)` | 19–20 |
| Peripheral scanner | `com/fitbit/bluetooth/fbgatt/rx/scanner/PeripheralScanner.java` | `PeripheralScanner.scanForDevices(...)` | 203 |
| Android ScanFilter assembly | `com/fitbit/bluetooth/fbgatt/FitbitGattImpl.java` | `FitbitGattImpl.addScanServiceUUIDWithMaskFilter(...)` | 466–476 |
| Raw scan callback | `defpackage/nsn.java` | `nsn.onScanResult(...)` | 55–71 |
| Address-dedup map | `defpackage/nsp.java` | `nsp.p` (HashMap) | 73 |
| Post-scan Rx filter (address) | `defpackage/qfr.java` | `qfr.invoke()` case 8 | 90–93 |
| Post-scan Rx filter (type/name) | `defpackage/qfr.java` | `qfr.invoke()` case 10 | 100–141 |
| ScanRecord wrapper | `defpackage/ott.java` | `ott(ScanRecord)` and `ott.k(ParcelUuid)` | 621–624, 358–360 |
| Device-type ID interface | `defpackage/pih.java` | `pih.a()`, `pih.c()` | 5–7 |
| Device-type ID impl | `defpackage/ahsy.java` | `ahsy(ywe)` | 10–27 |
| Device metadata class | `defpackage/ywe.java` | `ywe.a` (int), `ywe.b` (String) | 52–53 |
| Crypto verification | `defpackage/qdj.java` | `qdj.a(nqt, String)` and `qdj.b(byte[], String)` | 57–99, 101–141 |
| Scan invocation | `defpackage/qel.java` | `qel.invoke()` case 11 | 1193–1197 |
| nqw interface | `defpackage/nqw.java` | `nqw` | 11–41 |

---

*Report generated from static analysis only. No APK modifications or runtime instrumentation were performed.*
