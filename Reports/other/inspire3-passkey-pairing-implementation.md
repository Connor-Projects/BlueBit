# Inspire 3 BLE Passkey Pairing Implementation

## Date: 2026-09-01
## Status: Implemented, Awaiting Physical Device Test

---

## Phase 1: Fitbit Pairing Mechanism Investigation

### Key Findings from APK Analysis

1. **Fitbit does NOT programmatically supply PINs**
   - The Fitbit app relies on Android's system pairing dialog for PIN/passkey entry
   - No `BluetoothDevice.setPin()` calls found in the decompiled APK
   - No custom pairing agent implementation found

2. **Fitbit's BondStateBroadcastReceiver** (`com.google.android.libraries.ktgatt.bonding.impl`)
   - Listens for `ACTION_PAIRING_REQUEST` and `ACTION_BOND_STATE_CHANGED`
   - Extracts `EXTRA_PAIRING_VARIANT` and `EXTRA_PAIRING_KEY` from pairing intents
   - Emits events to an internal event bus but does NOT programmatically respond
   - Relies on the system dialog or `createBond()` to trigger Android's built-in flow

3. **CreateBondTransaction** (`defpackage/ntf.java`)
   - Registers `BOND_STATE_CHANGED` receiver
   - Calls `device.createBond()`
   - Waits up to 120 seconds for bonding to complete
   - Does NOT intercept or supply PINs programmatically

4. **Android BLE Pairing Mechanism (Passkey Entry)**
   - Device (Inspire 3) displays a 4-digit passkey
   - Phone receives `ACTION_PAIRING_REQUEST` with `PAIRING_VARIANT_PASSKEY_ENTRY` (variant=2)
   - Two options:
     a. Call `device.setPin(pinBytes)` programmatically (what BlueBit now does)
     b. Let Android show system dialog (what Fitbit app does)

---

## Phase 2: BlueBit Implementation

### Files Created

1. **`Inspire3PairingHandler.kt`** - Core pairing logic
   - Registers `ACTION_PAIRING_REQUEST` and `ACTION_BOND_STATE_CHANGED` receivers
   - Calls `device.createBond()` to initiate pairing
   - When `PAIRING_VARIANT_PASSKEY_ENTRY` received:
     - Signals PIN needed via `CompletableDeferred`
     - Invokes suspend callback to get PIN from user UI
     - Calls `BluetoothDevice.setPin(pinBytes)` with entered PIN
   - Tracks bond state transitions
   - Returns `true` when `BOND_BONDED` reached, `false` on failure/timeout
   - 60-second timeout with proper cleanup

2. **`PairingDialog.kt`** - Compose PIN entry UI
   - 4-digit numeric input field with validation
   - Shows device name and address
   - "Pair" button (enabled when 4 digits entered)
   - "Cancel" button to abort pairing
   - Returns PIN string to caller

### Files Modified

1. **`MainActivity.kt`**
   - Added `Inspire3PairingHandler` instance
   - Added pairing dialog state variables (`showPairingDialog`, `pairingDeviceName`, `pairingDeviceAddress`, `pairingPinDeferred`)
   - Added `pairWithInspire3(address: String)` method:
     - Validates BluetoothAdapter and permissions
     - Calls `pairingHandler.pairDevice()` with suspend PIN callback
     - Shows `PairingDialog` when PIN requested
     - Updates `bondState` on success/failure
   - Integrated `PairingDialog` into `setContent` Compose tree
   - Added `onPairDevice` callback passed to `BlueBitAppShell`
   - All existing diagnostics (GATT, Gattlink, GoldenGate) preserved

2. **`BlueBitAppShell.kt`**
   - Added `onPairDevice: (String) -> Unit` parameter
   - Passed through to `DevicesScreen`

3. **`DevicesScreen.kt`**
   - Added `onPairDevice: (String) -> Unit` parameter
   - Added "Pair" button next to each discovered device in scan list
   - Uses `SecondaryButton` component

---

## Pairing Flow Architecture

```
User taps "Pair" on discovered device
         |
         v
MainActivity.pairWithInspire3(address)
         |
         v
Inspire3PairingHandler.pairDevice(device, onPinRequired)
         |
         +---> Register BOND_STATE_CHANGED receiver
         +---> Register ACTION_PAIRING_REQUEST receiver
         +---> device.createBond()
         |
         v
   [Wait for events...]
         |
    +----+----+
    |         |
    v         v
BOND_BONDED  PAIRING_REQUEST (PASSKEY_ENTRY)
    |         |
    |         v
    |    pinNeededDeferred.complete(Unit)
    |         |
    |         v
    |    Handler loop detects PIN needed
    |         |
    |         v
    |    onPinRequired(device) suspend callback
    |         |
    |         v
    |    MainActivity: showPairingDialog = true
    |    PairingDialog shown to user
    |         |
    |         v
    |    User enters 4-digit PIN + taps "Pair"
    |         |
    |         v
    |    pairingPinDeferred.complete(pin)
    |    callback returns PIN
    |         |
    |         v
    |    device.setPin(pin.toByteArray(UTF_8))
    |         |
    v         v
[Both paths converge]
         |
         v
   BOND_BONDED reached?
         |
    +----+----+
    |         |
    v         v
   true     false
    |         |
    v         v
  Success   Failure
    |         |
    v         v
  Return    Return
```

---

## Key Design Decisions

1. **Programmatic PIN entry** (not system dialog)
   - Unlike Fitbit which relies on Android's system dialog
   - BlueBit uses `BluetoothDevice.setPin()` for automation/control
   - Enables headless/embedded scenarios

2. **Suspend callback for PIN**
   - `onPinRequired: suspend (BluetoothDevice) -> String`
   - Keeps pairing logic sequential and readable
   - UI code (dialog) is decoupled from pairing logic

3. **Busy-wait loop with delay**
   - Checks bond state and PIN needed every 100ms
   - Simple but effective; timeout prevents infinite loops
   - Runs inside `withTimeoutOrNull(60s)`

4. **Receiver cleanup in `finally` block**
   - Prevents memory leaks
   - Handles early cancellation/activity destruction

---

## Build Verification

```
BUILD SUCCESSFUL in 15s
37 actionable tasks: 6 executed, 31 up-to-date
APK: BlueBitApp/app/build/outputs/apk/debug/app-debug.apk (21.3 MB)
```

---

## Phase 3: Testing Instructions (Pending Physical Device)

1. Install APK: `adb install BlueBit-debug-pairing.apk`
2. Put Inspire 3 in pairing mode (factory reset or fresh out-of-box)
3. Open BlueBit app
4. Tap "Scan for Devices"
5. When Inspire 3 appears, tap "Pair"
6. Enter the 4-digit code shown on Inspire 3 screen
7. Observe logcat: `adb logcat -s BlueBitPairing:*`
8. Verify logs show:
   - `PAIRING_VARIANT_PASSKEY_ENTRY`
   - `PIN_ENTERED: Supplying to Android`
   - `SET_PIN_RESULT=true`
   - `BOND_BONDED: Pairing complete!`
   - `PAIRING_RESULT=true`

---

## Known Limitations / Risks

1. **`setPin()` is deprecated** in Android API but still functional
   - Alternative: use system pairing dialog (less control)
   - Risk: May be removed in future Android versions

2. **No retry logic**
   - If PIN is wrong, bond fails with `BOND_NONE`
   - User must tap "Pair" again after device resets

3. **Threading assumptions**
   - `pairDevice` uses `Dispatchers.Main` for receiver registration
   - `setPin()` must be called from main thread on some Android versions

4. **Untested with real hardware**
   - Flow verified via code review and compilation only
   - Actual Inspire 3 pairing behavior may differ

---

## Next Steps

1. Test with physical Inspire 3 device
2. Capture logcat during pairing attempt
3. If pairing fails, investigate:
   - Whether Inspire 3 uses `PAIRING_VARIANT_PASSKEY_ENTRY` (variant=2)
   - Whether PIN needs to be supplied as ASCII bytes vs numeric value
   - Whether `setPairingConfirmation(true)` is needed in addition to `setPin()`
   - Whether BLE connection must be established before `createBond()`
4. Document test results and any fixes needed
