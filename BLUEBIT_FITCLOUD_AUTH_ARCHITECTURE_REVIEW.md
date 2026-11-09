# BlueBit — FitCloud Authentication Architecture & Pairing Safety Review

Design review based on `FITCLOUD_CONNECTOR_STATE_ANALYSIS.md` and
`FITCLOUD_AUTH_TOKEN_LIFECYCLE_ANALYSIS.md` (SDK 3.0.2.4 bytecode-verified),
plus inspection of BlueBitApp source. Research/design only — no code changes.

Sources audited:

- `app/.../fitcloud/AndroidFitCloudProvider.kt`
- `app/.../fitcloud/FitCloudSdkInit.kt`
- `app/.../core/pairing/PairedDeviceCoordinator.kt`, `PairedDeviceRecord.kt`
- `app/.../core/connection/DiscoveryState.kt` (`ConnectionState`)
- `app/.../core/provider/WearableProvider.kt`
- `app/.../androidMain/.../pairing/SharedPreferencesPairedDeviceStore.kt`
- `app/.../BlueBitAppShell.kt` (UI connect/disconnect path)
- `app/.../MainActivity.kt` (coordinator wiring, diagnostics)
- `app/.../fitcloud/native/P6Connector.kt`, `P6AuthPackets.kt` (native reimplementation)

---

## 1. Authentication safety

### Confirmed SDK behaviour (recap, bytecode-verified)

- `bindOrLogin=true` → `AuthMode.BIND`; `false` → `AuthMode.LOGIN`. There is no
  way to express AUTO through BlueBit's current overloads.
- **`AuthMode.LOGIN` is NOT login-only.** On auth result byte `1` the SDK's
  `operation.a$a` signal triggers `onErrorResumeNext` → `doBindAuth`
  (`operation.a$i`, `a$g`). A LOGIN attempt that the watch answers "bind
  required" **silently upgrades to a full BIND**, which overwrites
  `token2_<mac>` and — per watch firmware behaviour BlueBit already comments
  on — may clear device data.
- **`AuthMode.AUTO` is also not safe as "login-first":** AUTO maps to
  `doLoginAuth(flag=false)`; result `1` produces `FcAuthException(1)`
  (`AUTH_LOGIN_FAILED`) with **no** bind fallback. AUTO is the only mode that
  cannot re-bind — but it also cannot recover a device that needs binding.
- There is **no genuinely login-only path** in `FcConnector.connect(...)`
  overloads. `operation.a` always permits the LOGIN→BIND upgrade on result 1.
- BIND sends only `userId` (+optional `code` via the explicit overload). The
  watch decides acceptance; no client-side guard prevents re-binding an
  already-bound device.

### Confirmed BlueBit behaviour

- `connect()` → `bindOrLogin=true` → **BIND** — used for *every* UI-initiated
  connect (`BlueBitAppShell.kt` `onToggleConnection` always calls
  `provider.connect`, never checks whether the device is already paired).
- `reconnect()` → `bindOrLogin=false` → **LOGIN** — used by
  `PairedDeviceCoordinator.restoreOnLaunch()` and its retry loop.
- `USER_ID = "bluebit_user"` is hard-coded for both paths (correctly
  consistent *within* BlueBit), but the P6 was previously bound via the
  official app identity `26544037` (used by `testConnectLogin`). If the watch
  still holds that binding, LOGIN with `bluebit_user` will return result `1`
  → **SDK auto-upgrades to BIND with `bluebit_user`** → potential re-bind /
  data clear on the user's device. This is the single largest pairing-safety
  hazard found.
- `pairedDeviceEvents` persists a `PairedDeviceRecord` on every `CONNECTED` —
  including connections initiated by `reconnect()` — so a record's existence
  does *not* prove which userId/mode created the binding.

### Answers

- **Can `reconnect()` unexpectedly trigger BIND?** Yes — whenever the watch
  answers LOGIN with result `1`. Trigger conditions: device bound to a
  different userId (e.g. `26544037`), binding cleared on watch, or watch-side
  binding expired.
- **Can the SDK's LOGIN fallback overwrite a binding?** Yes — result-1
  fallback is a real `doBindAuth` exchange, not a flag flip.
- **Genuinely login-only path?** None via `FcConnector`. Options:
  (a) accept the fallback but treat result-1-then-BIND as an explicit
      "re-pair required" event surfaced to the user *before* it happens —
      impossible, the SDK does it inline; (b) use the **native P6Connector**
      path (`P6AuthMode.LOGIN`) which throws
      `P6ProtocolException("bind step required")` instead of binding —
      BlueBit already has this code; (c) pre-flight: attempt LOGIN only when
      a `PairedDeviceRecord` exists for the address, and require explicit
      user confirmation before any `connect()` BIND.
- **Official account identity?** For devices bound by the official FitCloud
  app, `26544037` is required for LOGIN to succeed. For BlueBit-owned
  bindings, any stable userId works but must be persisted per device and
  never changed. Recommendation: store `userId` in `PairedDeviceRecord.extra`
  at pairing time; do not hard-code one global ID.
- **First-time pairing vs reconnection:** currently distinguished only by
  *which method is called*, and the UI always calls `connect()` (BIND). The
  coordinator restores via `reconnect()`, but a user tapping "Connect" on an
  already-paired device re-runs BIND. There is no pairing-state check.

---

## 2. Recommended pairing lifecycle

BlueBit's `ConnectionState` (`DiscoveryState.kt`) already has
`Disconnected / Connecting / Connected / DiscoveringServices / Disconnecting /
Error / Failed` — no dedicated auth/pairing states. `DiscoveryState` is
scan-side only.

### Proposed state additions (commonMain `ConnectionState`)

```
UNPAIRED          → no PairedDeviceRecord for address (or record marked stale)
PAIRING           → BIND in flight (user-initiated, destructive-capable)
AUTHENTICATING    → LOGIN in flight for a known-paired device
INITIALIZING      → SDK PRE_CONNECTED phase (auth done, init ops running)
CONNECTED         → SDK CONNECTED
REPAIR_REQUIRED   → auth result indicates binding lost / LOGIN→BIND would fire
FAILED(reason)    → terminal auth failure (AUTH_LOGIN_FAILED, AUTH_BIND_FAILED)
```

Map onto existing enum minimally: reuse `Connecting` for
PAIRING+AUTHENTICATING, `DiscoveringServices`→rename semantics to
INITIALIZING, add `Pairing`/`Authenticating`/`RepairRequired` data objects,
and route `Failed` for terminal auth errors.

### Lifecycle flows

| Scenario | Path |
|---|---|
| First-time pairing | scan → no record → user taps Connect → `PAIRING` (BIND) → success → persist record `{userId, authMode=BIND}` → `CONNECTED` |
| Existing-device restore | launch → record exists → `AUTHENTICATING` (LOGIN with stored userId) → success → `CONNECTED`; result-1 risk handled per §3 |
| Auth failure | `AUTH_LOGIN_FAILED`/`AUTH_BIND_FAILED` → `FAILED`/`REPAIR_REQUIRED`; **no auto-retry** (SDK already won't retry; coordinator must not either) |
| Bluetooth interruption | transport drop → SDK `AutoReconnectMode.BALANCED` retries (PRE_CONNECTING) → coordinator backoff as second layer; on terminal disconnect → bounded retry only if record exists and last failure wasn't auth |
| User unpair | `forget()` → `userDisconnect` → `provider.disconnect` → `connector.close()` → remove record; **do not** clear SDK `token2_` (harmless, per-MAC) |
| App restart | `restoreOnLaunch` → LOGIN per record; SDK prefs (`fc_internal_storage`) persist across restarts — `token2_` survives but is unused by auth decisions |

### States BlueBit has vs missing

- Has: `Disconnected, Connecting, DiscoveringServices(≈INITIALIZING),
  Connected, Disconnecting, Error, Failed`.
- Missing: explicit `Pairing`, `Authenticating`, `RepairRequired`,
  `AwaitingUserAction`; and any carrier for auth-specific failure reasons.

---

## 3. Error propagation

### Confirmed SDK surface

`FcConnector` exposes: `observerConnectorState()`, `observerConnectorError()`
(`ConnectorError` with retry info), `getDisconnectedCause()`,
`getDisconnectedReason()` → `DisconnectedReason` enum including
`AUTH_LOGIN_FAILED`, `AUTH_BIND_FAILED`, `BtDisabled`, `ErrorUnknown`,
`TemporaryDisconnected`, `AutoReconnectDisabled`, DFU/init-state variants.
`FcAuthException` implements `InterruptedConnectionException` → aborts retry,
emits `DISCONNECTED` **without** a `ConnectorError` onNext.

### Current BlueBit loss

`AndroidFitCloudProvider` subscribes only to `observerConnectorState()`;
`observerConnectorError()`, `getDisconnectedCause()`,
`getDisconnectedReason()` are never called. `ConnectionState.Error/Failed`
exist but are never emitted by the provider. UI shows only
`connectionStatusNote` from synchronous `connect()` `Result` (which is
`success` for any accepted call — auth outcome is invisible).

### Recommended propagation

```
FcConnector
  ├─ observerConnectorState()  → ConnectionState (existing mapping + new states)
  ├─ observerConnectorError()  → ProviderError.Connector(error, retryNumber)
  └─ on DISCONNECTED → getDisconnectedReason()/getDisconnectedCause()
        → ProviderError.Auth(reason: AUTH_LOGIN_FAILED|AUTH_BIND_FAILED)
          or ProviderError.Transport(BtDisabled|Timeout|Gatt|Unknown)
                ↓
WearableProvider.errorEvents: Flow<ProviderError>   (new)
                ↓
PairedDeviceCoordinator: suppress retry when error is Auth/*terminal*
                ↓
UI: ConnectionState.Failed(reason) + actionable note
    ("Watch needs re-pairing" vs "Bluetooth off" vs "Device unreachable")
```

Suggested commonMain types:

```kotlin
sealed interface ProviderError {
    val deviceKey: String
    data class Auth(val reason: AuthFailure) : ProviderError   // LoginFailed, BindFailed, RepairRequired
    data class Transport(val kind: TransportFailure) : ProviderError // BtDisabled, Timeout, Gatt, ScanFailed
    data class Retry(val attempt: Int, val nextDelayMs: Long) : ProviderError
}
enum class AuthFailure { LOGIN_FAILED, BIND_FAILED, BIND_REQUIRED, UNKNOWN }
```

Error mapping table:

| SDK signal | ProviderError | Coordinator action | UI |
|---|---|---|---|
| `DisconnectedReason.AUTH_LOGIN_FAILED` | `Auth(LOGIN_FAILED)` | stop retries, mark `REPAIR_REQUIRED` | "Login rejected — re-pair?" |
| `DisconnectedReason.AUTH_BIND_FAILED` | `Auth(BIND_FAILED)` | stop retries | "Pairing rejected by watch" |
| LOGIN result-1→BIND upgrade observed (via state re-entry into auth) | `Auth(BIND_REQUIRED)` | require user confirm before allowing BIND | "Watch requires re-binding — continue?" |
| `BtDisabled` / BLE off | `Transport(BtDisabled)` | pause retries until BT on | "Bluetooth is off" |
| `ConnectorError` retries | `Retry(n, delay)` | surface progress, keep coordinator backoff subordinate | "Reconnecting (n)…" |
| GATT/timeout/unknown | `Transport(...)` | bounded retry per policy | generic failure note |

Concurrency rule: **SDK auto-reconnect and coordinator retries must not both
fire.** Currently both are active (`AutoReconnectMode.BALANCED` in provider
init + coordinator `retryLoop`). Recommendation: keep SDK auto-reconnect as
the *only* reconnect engine for transport drops; coordinator retries only
`restoreOnLaunch`-style cold starts and only when `connectionState ==
Disconnected` persisted — i.e. make coordinator retry conditional on the SDK
having given up (`AutoReconnectDisabled`/terminal `DisconnectedReason`), not
on every `Disconnected` emission. Today `observeConnectionState` treats any
`Connected→Disconnected` transition as unexpected-drop and launches retries
that can collide with in-flight SDK reconnects — duplicate `connect()` calls
on `FcConnector` are not serialized by BlueBit.

`newPreConnectedSignal()` duplicate-auth risk: SDK calls it when auth config
(userId/mode/code) *changes* while ≥PRE_CONNECTED. Since BlueBit always sends
the same `USER_ID`, `authChanged` should be false on reconnect — but
`sex/age/height/weight` are hard-coded constants, also stable. Risk is low;
verify `operation.a.a(mode, code, userId)` returns false for identical args
(it compares stored fields — confirmed it does). Keep userId/userInfo
constants stable per device to avoid mid-session re-auth.

Race: `pairedDeviceEvents` (CONNECTED → upsert) vs `observeConnectionState`
(DISCONNECTED → retry decision) — a fast connect→drop could persist a record
then immediately schedule retries; acceptable, but auth-failure
`DISCONNECTED` must not create a managed record — it won't, since events only
fire on CONNECTED. However a device that pairs once then permanently fails
LOGIN will retry-loop forever under current policy (3 attempts × launches) —
acceptable bounded, but each retry risks the LOGIN→BIND upgrade. **Gate
coordinator retries on "not auth-failed" once error propagation lands.**

---

## 4. Identity & persistence

- **Where to store userId:** `PairedDeviceRecord.extra["fitcloud_user_id"]`,
  written at first successful BIND (from the value passed to `connect()`),
  read by `reconnect()`. Per-device, not global — supports mixed fleets
  (some devices bound to official-app identity, some to BlueBit identity).
- **PairedDeviceRecord additions:** `authMode`/`boundUserId`/`boundAt` in
  `extra` suffice — no schema change needed since `extra` is already a
  provider-namespaced map persisted by `SharedPreferencesPairedDeviceStore`.
- **Preventing identity drift:** never derive userId from device name/address;
  single source = record. Hard-coded `USER_ID` should become a *default for
  new pairings only*. `testConnectLogin`'s `26544037` must stay diagnostic-only
  and never write records.
- **SDK token (`token2_<mac>`):** BlueBit should **not** persist or manage it.
  It's per-MAC in SDK private prefs, auto-created/refreshed, unused in auth
  decisions, survives reinstall-of-record but not app uninstall. Copying it
  adds no value and risks divergence. Only caveat: it is overwritten on every
  BIND — harmless since unused.
- **Unpair:** `forget()` already removes the record; nothing else needed —
  watch-side binding remains (user may need official app or BIND to reclaim).

---

## 5. Connection lifecycle & concurrency findings

| Risk | Status |
|---|---|
| SDK auto-reconnect vs coordinator retries overlap | **Real.** Both active; coordinator retries on every unexpected `Disconnected`, SDK retries internally with BALANCED backoff. `provider.reconnect()` calls `connector.connect()` again — SDK may already be mid-reconnect. Needs single-owner policy (§3). |
| Duplicate auth via `newPreConnectedSignal()` | Low while userId/profile args are constant; `operation.a.a(...)` returns false for unchanged config → no re-signal. Guard by keeping per-device identity stable. |
| State updates racing pairing persistence | `pairedDeviceEvents` only fires on CONNECTED — persistence can't precede auth success. Safe. |
| Overlapping `connect()` calls | UI guards with `isProcessing`; coordinator `retryJobs` map prevents duplicate loops per key, but `restoreOnLaunch` + user tap + SDK auto-reconnect can still triple-fire. Add a per-provider connect mutex (like sample's `clearDeviceMutex`). |
| Multi-device | `FcConnector` is a singleton — **one FitCloud device at a time**. `managed` map may hold several records but only one can be connected; coordinator should enforce single-active-device for `fitcloud` provider. |

---

## 6. Proposed architecture

### State diagram

```
                 ┌─────────────┐
   scan found ──► │  UNPAIRED   │
                 └──────┬──────┘
            user Connect│ (no record)
                        ▼
                 ┌─────────────┐   auth ok    ┌──────────────┐
                 │  PAIRING    │─────────────►│ INITIALIZING │──► CONNECTED
                 │  (BIND)     │              │ (SDK PRE_CONN│
                 └──────┬──────┘              └──────┬───────┘
            record exists│                          │ drop
                        ▼                            ▼
                 ┌─────────────┐            transport retry (SDK+coordinator,
                 │AUTHENTICATING│            single owner) → back to AUTH
                 │  (LOGIN)     │
                 └──────┬──────┘
        result 0 ───────┤────────── result 1 (bind required)
                        ▼                          ▼
                  CONNECTED path            ┌───────────────┐
                        │                  │ REPAIR_REQUIRED│
                        ▼                  │ (user confirm │
                  terminal auth failure ──►│  → PAIRING)   │
                  (LOGIN_FAILED/BIND_      └───────────────┘
                   FAILED) → FAILED
```

### Auth decision flow (provider-level)

```
connectRequested(device):
  record = store[device.address]
  if record == null:
      requireUserConfirm("Pair new watch? This may erase watch data.")
      mode = BIND; userId = DEFAULT_USER_ID (or user-entered)
  else:
      mode = LOGIN; userId = record.extra["fitcloud_user_id"]
      on AUTH_LOGIN_FAILED → offer "Re-pair?" → only then BIND
  persist {userId, mode} into record only after CONNECTED
```

Note: SDK's implicit LOGIN→BIND upgrade cannot be intercepted — mitigate by
(a) never calling LOGIN for unpaired devices, (b) surfacing
`AUTH_LOGIN_FAILED`/re-entry into auth as `REPAIR_REQUIRED` and requiring
explicit BIND next time, (c) optionally adopting `P6Connector` native path
for strict login-only semantics where the SDK fallback is unacceptable.

### Suggested interfaces (commonMain)

```kotlin
// WearableProvider.kt — additive
val authEvents: Flow<AuthEvent> get() = emptyFlow()
suspend fun connect(device: BleDevice, connection: BleConnection,
                    auth: AuthIntent): Result<Unit>   // or overload

data class AuthIntent(val mode: AuthMode, val userId: String)
enum class ProviderAuthMode { BIND, LOGIN }   // deliberately no AUTO

data class PairedDeviceRecord( /* existing */
    // extra keys (fitcloud): "fitcloud_user_id", "fitcloud_auth_mode", "fitcloud_project"
)
```

### Migration considerations

- Existing installs may already have records **without** `fitcloud_user_id`
  (current `pairedDeviceEvents` writes only `project`). Migration: on first
  `reconnect()` for such a record, fall back to `USER_ID` constant; if LOGIN
  fails, mark `REPAIR_REQUIRED` rather than letting SDK auto-BIND silently —
  requires the error-propagation work first, otherwise the fallback is
  invisible.
- `testConnectLogin` must not emit `pairedDeviceEvents` records for
  `26544037` sessions unless intentionally paired — currently it shares the
  same connector/state flow, so a successful diagnostic login **will** create
  a `PairedDeviceRecord` via `pairedDeviceEvents`. Flag diagnostics to
  suppress persistence.
- Two `WearableProvider` FitCloud implementations exist
  (`AndroidFitCloudProvider` real, `FitCloudProvider` stub returning
  `NotImplementedError`). Ensure registry uses the Android one; the stub's
  `connect` failure is safe but confusing.

### Tests required before implementation

1. Unit: `PairedDeviceCoordinator` — auth-failure error suppresses retryLoop;
   `REPAIR_REQUIRED` blocks auto-reconnect; `forget` during retry cancels.
2. Unit: provider error mapping — `DisconnectedReason.*` → `ProviderError`.
3. Integration (emulator/mock connector): LOGIN result-1 path → assert no
   silent BIND when record absent; BIND only after explicit confirm.
4. Regression: `authChanged` stays false across reconnect with identical
   userId/profile (no duplicate auth).
5. Device test (P6, later): verify whether watch enforces same-userId on
   LOGIN and whether BIND overwrites binding — hardware-dependent, cannot be
   resolved statically.

---

## 7. Confirmed vs. unresolved

**Confirmed:** all SDK auth semantics (§1), BlueBit's BIND-on-every-connect,
LOGIN-with-fallback on reconnect, dropped error channels, dual retry engines,
single-connector constraint, record schema.

**Unresolved (hardware/device-side):**

1. Does the P6 enforce that LOGIN userId == binding userId? (Determines
   whether `bluebit_user` LOGIN against a `26544037` binding returns result 1
   — and thus triggers the destructive fallback.)
2. Does BIND on an already-bound device clear watch data, and does the watch
   confirm/deny re-bind to a *different* userId?
3. Does watch-side binding survive app uninstall / `token2_` prefs loss?
   (Client token unused in auth, but watch may track binding independently.)
4. Whether `P6Connector`'s native implementation is protocol-complete enough
   to replace `FcConnector` for strict login-only flows (it already models
   `RESULT_LOGIN_BIND_REQUIRED` → throws instead of binding — promising).
