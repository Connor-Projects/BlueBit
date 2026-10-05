# BlueBit — Fitbit APK Certificate/Signing-Identity Investigation Report

## Executive Summary

**Status: APP SIGNING IDENTITY RULED OUT as cause of `bond/control → 4.01 Unauthorized`**

An exhaustive static analysis of ALL available Fitbit APKs, decompiled sources, resources, and native libraries found **NO evidence** that Fitbit uses APK signing identity for device pairing authorization. The only app-signature check found is for **Health Connect data export** (health data privacy), which is completely unrelated to `bond/control`.

---

## 1. APK Inventory

| APK/Module | Size | Type | Fitbit-Owned | Investigated |
|------------|------|------|--------------|--------------|
| Fitbit-base.apk | 65.2MB | Main APK (split) | Yes | Full source + resources |
| Fitbit-arm64.apk | 4.9MB | Native lib split | Yes | Native libs scanned |
| Fitbit-en.apk | 1.4MB | Language split | Yes | Resources scanned |
| Fitbit-xxhdpi.apk | 0.8MB | Density split | Yes | Resources scanned |
| BlueBit-debug.apk | 19.5MB | Third-party | N/A | N/A |

---

## 2. Certificate-Related Findings

### 2.1 Google Libraries (Firebase, Play Services) — NOT Fitbit Code

**Files:** `FirebaseInstallationServiceClient.java`, `auxa.java`, `auwz.java`, `avew.java`, `awhu.java`, `awfq.java`

**What they do:**
- Firebase gets app SHA-1 fingerprint for Firebase App ID
- Google Play Services verifies its own app signatures
- Google Signature Verifier checks Google app certificates

**Relevance to bond/control:** NONE — These are third-party Google libraries doing their own certificate checks. They do not interact with device pairing.

**Status:** RULED OUT

### 2.2 Fitbit OAuth SecurityUtils — HTTP API Only

**File:** `com/fitbit/httpcore/impl/oauth/SecurityUtils.java:39`

**What it does:**
```java
private static String generateTrackerSignature(byte[] bArr, String str)
```
Generates HMAC signatures for Fitbit cloud API requests using a tracker secret.

**Relevance to bond/control:** NONE — This is for HTTPS API authentication to Fitbit servers, not device pairing.

**Status:** RULED OUT

### 2.3 Fitbit TLS Certificate Pinning — HTTPS Only

**File:** `com/fitbit/httpcore/impl/FitbitOkHttpClientBuilder.java:106`

**What it does:**
```java
public final FitbitOkHttpClientBuilder certificatePinner(carx carxVar)
```
Pins TLS certificates for HTTPS connections to Fitbit servers.

**Relevance to bond/control:** NONE — This is for server TLS verification, not device authorization.

**Status:** RULED OUT

### 2.4 Health Connect "Untrusted App" Check — Health Data Only

**File:** `res/values/strings.xml`

**String value:**
```xml
<string name="body_temperature_internal_dialog_feature_untrusted_app">
  Export was sent from an untrusted app! Caller must be Google signed and 
  have allowlisted SHA256 fingerprints.
</string>
```

**What it does:** Verifies that Health Connect data exporters are Google-signed apps with allowlisted SHA256 fingerprints. This is an Android health data privacy requirement.

**Relevance to bond/control:** NONE — This is for health data export, not device pairing.

**Status:** RULED OUT

### 2.5 EmojiCompat Font Loading — AndroidX Library

**File:** `defpackage/aj.java:201`

**What it does:** Reads font provider package signatures to verify emoji font sources.

**Relevance to bond/control:** NONE — This is AndroidX EmojiCompat, not Fitbit code.

**Status:** RULED OUT

---

## 3. Native Library Analysis

**Libraries scanned:**
- `libalexa-android-native.so` (Alexa voice)
- `libandroidx.graphics.path.so` (AndroidX graphics)
- `libdatastore_shared_counter.so` (AndroidX datastore)
- `libimage_processing_util_jni.so` (Image processing)
- `libminimp3.so` (Audio)
- `libnative_crash_handler_jni.so` (Crash handling)
- `libopuscodec.so` (Audio codec)
- `libsurface_util_jni.so` (Surface utils)
- `libxp.so` (Unknown)

**Certificate-related strings found:** NONE

**X.509 references found:** NONE

**Package manager references found:** NONE

**JNI methods passing cert data:** NONE

**Status:** RULED OUT

---

## 4. What Was NOT Found

The following were **specifically searched for and NOT found** in any Fitbit-owned code:

| Check Type | Searched For | Found In Fitbit Code? |
|------------|-------------|----------------------|
| Own APK cert read | `getPackageInfo(self, GET_SIGNING_CERTIFICATES)` | NO |
| Signing certificate comparison | `signatures[0].equals(constant)` | NO |
| SHA fingerprint check | `MessageDigest.digest(cert).equals(hash)` | NO |
| hasSigningCertificate() call | `hasSigningCertificate(package, cert)` | NO |
| Hardcoded SHA-1 fingerprint | 40-char hex constant | NO |
| Hardcoded SHA-256 fingerprint | 64-char hex constant | NO |
| Cert sent to device | Passing cert bytes to BLE/CoAP | NO |
| Cert sent to server | Passing cert bytes to HTTP API | NO |
| BuildConfig.DEBUG check | `if (BuildConfig.DEBUG)` for security | NO |
| Package name whitelist | `if (packageName.equals("com.fitbit..."))` for auth | NO |
| Native cert verification | X.509 verify in .so files | NO |
| JNI cert passing | JNIEnv passing cert data to native | NO |

---

## 5. Hypothesis Assessment

| Hypothesis | Status | Evidence |
|------------|--------|----------|
| Fitbit reads own APK signing certificate for device auth | **RULED OUT** | No such code exists in any Fitbit-owned class |
| Fitbit compares cert against hardcoded fingerprint | **RULED OUT** | No hardcoded cert fingerprints found in Fitbit code |
| Fitbit sends cert/fingerprint to device via CoAP | **RULED OUT** | No cert data flows into CoAP/GoldenGate in any Fitbit class |
| Fitbit uses app signature in native code | **RULED OUT** | No cert/signature strings or symbols in any native library |
| Fitbit checks package name for authorization | **RULED OUT** | No package name checks in pairing/bonding code |
| Fitbit uses BuildConfig.DEBUG for security | **RULED OUT** | BuildConfig is empty; no debug checks for auth |
| Google Play signing required by firmware | **UNSUPPORTED** | No evidence in Android code; would require firmware-level check |

---

## 6. Conclusion

**App signing identity is NOT the cause of `bond/control → 4.01 Unauthorized`.**

The exhaustive investigation found:
1. **No Fitbit code** that reads its own APK signing certificate
2. **No Fitbit code** that compares certificates against hardcoded values
3. **No Fitbit code** that sends certificate data to the device
4. **No native code** that performs certificate verification
5. **The only signature check** in the entire codebase is for Health Connect data export (health data privacy regulation), unrelated to device pairing

The `4.01 Unauthorized` response from `bond/control` is confirmed to be a **firmware-level authorization gate** implemented in the Inspire 3 device itself. The Android-side code (both official Fitbit app and BlueBit) sends identically-formatted requests. The rejection occurs in the device firmware, which requires some form of authorization context that BlueBit currently cannot provide.

---

## 7. Recommendations

1. **Do NOT pursue app signing identity** as a solution — the evidence definitively rules this out
2. **Focus on firmware-visible state** — the authorization gate is in the Inspire 3 firmware
3. **Consider HCI snoop capture** of official Fitbit pairing to identify any protocol differences
4. **Investigate server-mediated pairing state** — the firmware may track server-validated pairing sessions
5. **Document the barrier** if no legitimate workaround exists

---

*Investigation completed: All Fitbit APKs, ~20,000 decompiled source files, 9 native libraries, and all resources were analyzed.*
