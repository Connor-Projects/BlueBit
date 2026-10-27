# FitCloud Pro BLE Protocol Findings

## Device Under Test
- **Name**: P6_
- **MAC Address**: 90:EB:99:D6:5F:99
- **Model**: FitCloud Pro smartwatch (generic P6-series)
- **Captured**: 2026-09-11 via BlueBit unfiltered BLE scan

## Advertisement Data

### Primary Service UUID
- **UUID**: `00003802-0000-1000-8000-00805F9B34FB` (short: `0x3802`)
- **Type**: 16-bit UUID expanded to 128-bit
- **Usage**: Advertised in scan results; used as FitCloud device discriminator

### Manufacturer Data
- **Company ID**: `23124` (0x5A54)
- **Note**: Payload bytes not fully logged in this capture

### Behavior
- Advertises strongly when unpaired (RSSI -50 to -61 dBm)
- **Stops advertising after bonding** via official FitCloud Pro app
- This is standard BLE behavior for bonded peripherals

## Scan Filter Implementation

```kotlin
// FitCloudUuids.kt
const val FITCLOUD_PRIMARY_SERVICE_UUID = "00003802-0000-1000-8000-00805F9B34FB"
```

`FitCloudProvider.canHandle()` matches any scan result advertising `0x3802`.

## GATT Protocol (Unknown — Pending Capture)

The following remain unknown until a GATT service dump is obtained:

| Item | Status | Notes |
|------|--------|-------|
| Full service tree | ❌ Unknown | Need `dumpServices()` output |
| Auth characteristic | ❌ Unknown | Likely under 0x3802 service |
| Notify characteristic | ❌ Unknown | Likely under 0x3802 service |
| Write characteristic | ❌ Unknown | Likely under 0x3802 service |
| CCCD descriptor | ❌ Unknown | Standard `0x2902` expected |
| MTU | ❌ Unknown | Negotiated post-connection |

## Next Steps

1. **Unpair** the watch from the official FitCloud Pro app
2. **Connect via BlueBit** directly (by address or scan)
3. **Tap "Dump GATT Services"** diagnostic
4. **Pull logs**: `adb logcat -d -s BlueBitBle > gatt_dump.txt`
5. Parse discovered services/characteristics into `FitCloudUuids.kt`
6. Implement `FitCloudProvider.connect()` handshake

## References

- FitCloud Pro SDK: `com.topstep.wearkit:sdk-fitcloud`
- SDK Repo: https://github.com/htangsmart/FitCloudPro-SDK-Android
- Maven: http://120.78.153.20:8081/repository/maven-public/
