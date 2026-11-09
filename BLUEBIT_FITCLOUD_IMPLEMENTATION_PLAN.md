# BlueBit — Minimal FitCloud Pairing Safety Implementation Plan

Plan only — no source modified. Builds on the four research reports; SDK
semantics below are **CONFIRMED** by 3.0.2.4/3.0.2.7 bytecode unless marked
otherwise.

Corrected auth semantics (do not regress):

| Mode | Reachable via | Result-1 behavior |
|---|---|---|
| `LOGIN` | `connect(..., bindOrLogin=false, …)` / `AuthMode.LOGIN` | `FcAuthException(1)` → `AUTH_LOGIN_FAILED`, **no BIND** |
| `BIND` | `connect(..., bindOrLogin=true, …)` / `AuthMode.BIND` | `FcAuthException(n)` |
| `AUTO` | `AuthMode.AUTO` overload only | **login → auto-BIND** — never use |

---

## 1. Current behaviour — CONFIRMED

| Call site | File:line | Effect |
|---|---|---|
| UI Connect/Disconnect toggle | `BlueBitAppShell.kt:~205–245` (`onToggleConnection`) | Always `p.connect(device, conn)` → `AndroidFitCloudProvider.connect` → **BIND** (`AndroidFitCloudProvider.kt:107–115`), regardless of pairing state. Disconnect → `userDisconnect` + `p.disconnect` → `connector.close()`. |
| Cold-start restore | `PairedDeviceCoordinator.restoreOnLaunch` (`PairedDeviceCoordinator.kt:60–69`) | `provider.reconnect` → **LOGIN** with hard-coded `USER_ID` |
| Unexpected-drop retry | `PairedDeviceCoordinator.retryLoop` (`:133–150`) | `provider.reconnect` → LOGIN, max 3 attempts |
| Diagnostic login | `MainActivity.kt:431–443` → `testConnectLogin` (`AndroidFitCloudProvider.kt:157–176`) | **LOGIN as `26544037` on the shared singleton connector** → emits `CONNECTED` → `pairedDeviceEvents` persists a record (`:256–273`) and mutates auth config |
| SDK auto-reconnect | provider init `setAutoReconnectMode(BALANCED)` (`:71`) | SDK-internal transport retries; re-runs auth op with stored mode |

BIND entry points that exist today: **only** `AndroidFitCloudProvider.connect()`
(via UI toggle). `reconnect()` and `testConnectLogin()` are LOGIN.
`AuthMode.AUTO` is never referenced — keep it that way.

Problems to fix (minimal set):

1. UI cannot distinguish paired vs unpaired → BIND fires on every tap.
2. Diagnostic session pollutes pairing store + auth config + coordinator.
3. No serialization around the singleton connector (UI `isProcessing` is
   per-screen only; coordinator retry + restore + diagnostic can overlap).
4. Auth failures invisible: only `observerConnectorState()` consumed;
   `observerConnectorError()`/`getDisconnectedReason()` unused →
   `AUTH_LOGIN_FAILED` looks identical to a transport drop, and coordinator
   retries re-issue LOGIN pointlessly.
5. `PairedDeviceRecord.extra` carries only `project` — no userId; records
   created before this change lack identity metadata.

---

## 2. Proposed minimal changes

### 2.1 Pairing-aware connect routing (smallest change)

Add to `WearableProvider` (default-implemented, no breakage for Fitbit):

```kotlin
suspend fun reconnect(device: BleDevice): Result<Unit> = connect(device, createConnection(device))
// NEW:
suspend fun connectForRecord(device: BleDevice, connection: BleConnection,
                             record: PairedDeviceRecord?): Result<Unit> =
    connect(device, connection)   // default: legacy behaviour
```

In `AndroidFitCloudProvider`, override `connectForRecord`:

```kotlin
val userId = record?.extra?.get(EXTRA_USER_ID) ?: USER_ID
val bind = record == null   // BIND only for genuinely new devices
connector.connect(device.address, userId, bindOrLogin = !bind, …)
```

In `BlueBitAppShell.onToggleConnection`, look up the record before calling:

```kotlin
val record = pairedDeviceStore?.load()?.firstOrNull {
    it.providerId == p.id && it.address == screen.deviceAddress }
val result = p.connectForRecord(device, conn, record)
```

Rules (all CONFIRMED-safe given SDK semantics):

- record exists **with** `fitcloud_user_id` → LOGIN with that userId.
- record exists **without** identity (legacy) → LOGIN with `USER_ID`
  (conservative: LOGIN cannot destroy; failure → `AUTH_LOGIN_FAILED` →
  surface "re-pair" prompt rather than auto-BIND).
- no record → require explicit confirmation ("Pair this watch? This may
  erase its data") → BIND with `USER_ID` → on `CONNECTED`,
  `pairedDeviceEvents` upserts the record — extend it to also write
  `extra["fitcloud_user_id"] = USER_ID` and `"fitcloud_auth_mode"="BIND"`.
- **A failed LOGIN never triggers BIND** — guaranteed by SDK LOGIN semantics;
  BlueBit must additionally *not* auto-retry auth failures (§2.4).

UI call sites that can initiate BIND after this change: exactly one —
the confirmed "Pair new device" path. `reconnect()`, coordinator retry,
restore, and diagnostics remain LOGIN.

### 2.2 Diagnostic isolation — smallest safe separation

CONFIRMED hazards: shared connector, shared state flow, shared auth config.

Minimal fix (no new connector stack needed):

```kotlin
// AndroidFitCloudProvider
private val diagnosticSession = AtomicBoolean(false)

suspend fun testConnectLogin(address: String): Result<Unit> =
    diagnosticSession.withTrue {
        connector.connect(address, OFFICIAL_APP_USER_ID, bindOrLogin = false, …)
    }
```

and gate emissions while set:

```kotlin
// in observerConnectorState() subscription:
if (!diagnosticSession.get()) _connectionState.value = …
// pairedDeviceEvents:
.filter { it == CONNECTED && !diagnosticSession.get() }
```

Residual race: a diagnostic CONNECTED arriving in the same instant the flag
flips — acceptable (INFERRED rare); stronger isolation = route diagnostics
through `P6Connector` (already exists, `P6NativeDiagnostic.kt:85`) which never
touches `fcSDK.connector` at all. **Recommended:** move `testConnectLogin`
to `P6Connector`-backed diagnostic and delete the SDK-connector diagnostic —
this removes auth-config mutation too, which the flag approach cannot fully
prevent (diagnostic LOGIN still overwrites `operation.a` userId until next
production connect restores it — CONFIRMED self-healing but racy).

Also add a provider-level connect mutex (§2.3) so a diagnostic can't overlap
a production connect even during the transition.

### 2.3 Connector concurrency — single ownership policy

All `connector.*` call sites (CONFIRMED complete list):
`AndroidFitCloudProvider.kt:107,130,161` (`connect` ×3),
`:277` (`close` via `disconnect`), plus SDK-internal auto-reconnect.

Proposal:

```kotlin
private val connectorMutex = Mutex()

suspend fun connect/reconnect/testXxx {
    connectorMutex.withLock { /* all connector.connect/close calls */ }
}
```

- UI already guards with `isProcessing`; mutex makes it race-proof across
  coordinator threads.
- `disconnect()`/`close()` also take the mutex.
- **Do not** disable SDK `AutoReconnectMode.BALANCED` — it is the transport
  recovery layer. Instead fix the coordinator: only launch `retryLoop` when
  the terminal reason is transport-class, not auth-class (§2.4). SDK
  auto-reconnect and coordinator retry then own disjoint cases:
  SDK owns in-session drops; coordinator owns cold-start restore and
  post-terminal-disconnect recovery.
- `newPreConnectedSignal()` duplicate-auth: not a concern while userId and
  profile args are stable per device (CONFIRMED `operation.a.a(...)` returns
  false for unchanged config). Keep per-device constants — never vary
  sex/age/height/weight per call.

### 2.4 Error propagation — minimal event surface

CONFIRMED: auth failure emits `DISCONNECTED` **without** `ConnectorError`
(InterruptedConnectionException aborts before `onNext`). Therefore
`getDisconnectedReason()`/`getDisconnectedCause()` must be sampled **on every
transition into DISCONNECTED** (and cached), not only in `observeError`.

Provider additions:

```kotlin
sealed interface FitCloudError {
    data object LoginRejected : FitCloudError      // AUTH_LOGIN_FAILED
    data object BindRejected : FitCloudError       // AUTH_BIND_FAILED
    data object BluetoothDisabled : FitCloudError
    data class Transport(val detail: String?) : FitCloudError
    data class Retrying(val attempt: Int) : FitCloudError
    data object Unknown : FitCloudError
}
val errorEvents: SharedFlow<FitCloudError>
```

Mapping (read at DISCONNECTED edge):

| `getDisconnectedReason()` | → |
|---|---|
| `AUTH_LOGIN_FAILED` | `LoginRejected` — terminal, no retry |
| `AUTH_BIND_FAILED` | `BindRejected` — terminal |
| `BtDisabled`/equivalent | `BluetoothDisabled` |
| other + `DisconnectedCause` interrupted/timeout | `Transport` |
| `ConnectorError` onNext (retriable path) | `Retrying(n)` |
| fallback | `Unknown` |

`WearableProvider` gets `val errorEvents: Flow<ProviderError>` (default
`emptyFlow()`); coordinator collects it and **skips retryLoop for
`LoginRejected`/`BindRejected`/`BluetoothDisabled`**. UI maps
`LoginRejected` → "Watch rejected login — re-pair?" with a button that routes
to the confirmed-BIND path; `BindRejected` → "Pairing rejected by watch".

### 2.5 Identity persistence

- Store `extra["fitcloud_user_id"]` + `extra["fitcloud_auth_mode"]` in
  `pairedDeviceEvents` (only on real CONNECTED — diagnostics gated per §2.2).
- `SharedPreferencesPairedDeviceStore` needs **no change** — `extra` map is
  already persisted generically (CONFIRMED `SharedPreferencesPairedDeviceStore.kt`).
- Legacy records (no userId): LOGIN with `USER_ID`; if that fails, prompt
  re-pair — never silently BIND.
- Diagnostic identity `26544037` must never be written to records — guaranteed
  once diagnostics leave the shared connector (or are gated).
- **Do not** read/write/persist `token2_<mac>` — SDK-managed, unused in auth
  (CONFIRMED).

---

## 3. State transitions (target)

```
UNPAIRED ──user taps Connect──► CONFIRM_PAIR ──confirm──► PAIRING(BIND)
                                                     └─decline──► UNPAIRED
PAIRING(BIND) ──result0──► CONNECTED ──persist record+userId──► PAIRED
          └─result≠0──► FAILED(BindRejected)          (terminal)
PAIRED ──restore/retry/dropped──► AUTHENTICATING(LOGIN, stored userId)
          ├─result0──► CONNECTED
          └─result1──► FAILED(LoginRejected) ──user "Re-pair?"──► PAIRING(BIND)
CONNECTED ──transport drop──► SDK auto-reconnect (PRE_CONNECTING…)
          └─terminal disconnect──► coordinator retryLoop (LOGIN), ≤3, backoff
                               └─auth-class error──► STOP, surface to UI
```

BlueBit `ConnectionState` needs no new enum members for v1: map
`LoginRejected`/`BindRejected` into existing `ConnectionState.Failed(reason)`
+ `errorEvents` for the actionable UI note. (`Pairing`/`Authenticating`
distinctions optional polish — `Connecting`/`DiscoveringServices` suffice.)

---

## 4. Files requiring modification

| File | Change |
|---|---|
| `core/provider/WearableProvider.kt` | add `errorEvents` (default empty), `connectForRecord` (default delegates) |
| `fitcloud/AndroidFitCloudProvider.kt` | mutex; `connectForRecord` BIND/LOGIN routing + per-record userId; `pairedDeviceEvents` writes `fitcloud_user_id`; error sampling on DISCONNECTED → `errorEvents`; diagnostic gate or removal |
| `fitcloud/diagnostics/P6NativeDiagnostic.kt` / `MainActivity.kt` | point `fc_connect` diagnostic at `P6Connector` path (or gate SDK diagnostic) |
| `core/pairing/PairedDeviceCoordinator.kt` | collect `errorEvents`; suppress retryLoop on auth/BT-disabled errors; keep SDK auto-reconnect as transport owner |
| `core/ui/screens/ConnectionPresentation.kt` + `BlueBitAppShell.kt` | pass record into `connectForRecord`; pair-confirmation dialog for unpaired; map `LoginRejected` to re-pair prompt |
| `core/pairing/PairedDeviceRecord.kt` | no schema change (`extra` suffices) — document key constants |

No changes: `FitCloudSdkInit.kt`, `SharedPreferencesPairedDeviceStore.kt`,
SDK dependencies, `P6Connector.kt` (consumed as-is by diagnostics).

---

## 5. Pseudocode — provider core

```kotlin
class AndroidFitCloudProvider(...) {
    private val connectorMutex = Mutex()
    private val _errors = MutableSharedFlow<FitCloudError>(extraBufferCapacity = 8)
    val errorEvents: SharedFlow<FitCloudError> = _errors.asSharedFlow()

    init {
        disposables.add(connector.observerConnectorState().subscribe { s ->
            _connectionState.value = mapState(s)
            if (s == ConnectorState.DISCONNECTED) classifyDisconnect()
        })
        disposables.add(connector.observerConnectorError().subscribe { e ->
            _errors.tryEmit(FitCloudError.Retrying(e.retryNumber))
        })
    }

    private fun classifyDisconnect() {
        _errors.tryEmit(when (connector.disconnectedReason) {
            AUTH_LOGIN_FAILED -> FitCloudError.LoginRejected
            AUTH_BIND_FAILED  -> FitCloudError.BindRejected
            BT_DISABLED       -> FitCloudError.BluetoothDisabled
            else -> FitCloudError.Transport(connector.disconnectedCause?.toString())
        })
    }

    override suspend fun connectForRecord(device, conn, record) =
        connectorMutex.withLock {
            val userId = record?.extra?.get(EXTRA_USER_ID) ?: USER_ID
            connector.connect(device.address, userId,
                bindOrLogin = record == null,   // BIND only when unpaired
                sex = true, age = 30, height = 175f, weight = 70f)
        }

    override val pairedDeviceEvents =
        connector.observerConnectorState().asFlow()
            .filter { it == CONNECTED && !diagnosticSession.get() }
            .map { PairedDeviceRecord(id, addr, name,
                extra = mapOf("project" to project,
                              "fitcloud_user_id" to lastUserId)) }
}
```

`lastUserId` is set inside `connectForRecord`/`reconnect` under the mutex —
guarantees the persisted identity is the one actually used.

---

## 6. Regression test plan

Unit (desktopTest, mock connector/provider):

1. `connectForRecord(record=null)` → `bindOrLogin=true`; with record → `false`.
2. Record with `fitcloud_user_id="26544037"` → reconnect passes that userId.
3. Legacy record (no userId) → LOGIN with `USER_ID`, never BIND.
4. `LoginRejected` error → coordinator `retryLoop` not started; state →
   `Failed`, not retried.
5. `Transport` error after Connected→Disconnected → retryLoop runs ≤3.
6. Diagnostic path → no `pairedDeviceEvents` emission, no record upsert.
7. Concurrent `connect`+`reconnect`+`disconnect` → serialized (mutex order).
8. `pairedDeviceEvents` writes `fitcloud_user_id` matching the userId passed.

Device (manual, later — P6): successful LOGIN reconnect with official
identity remains green; BIND only via explicit pair action.

---

## 7. Migration risks

- **Existing records lack `fitcloud_user_id`** → LOGIN as `bluebit_user` may
  return result 1 if the P6 is still bound to `26544037` →
  `AUTH_LOGIN_FAILED` → UI offers re-pair. This is *desired* conservative
  behaviour; document for users. UNKNOWN whether watch enforces userId match.
- Records created by diagnostic sessions may already exist in stores —
  consider a one-time purge of `fitcloud` records lacking
  `fitcloud_auth_mode="BIND"` provenance, or leave (LOGIN-fail → re-pair is
  self-correcting).
- `connectForRecord` default delegates to `connect` → Fitbit unaffected.
- Removing SDK diagnostic changes dev workflow — keep `P6Connector`
  diagnostic as the replacement (it already supports `P6AuthMode.LOGIN`).

## 8. Implementation order

1. Provider mutex + `errorEvents` + DISCONNECTED classification (no behaviour
   change to auth modes).
2. `connectForRecord` routing + record-aware UI (BIND only on confirmed new
   pairing).
3. `pairedDeviceEvents` userId persistence.
4. Coordinator retry gating on error class.
5. Diagnostic isolation (gate first, migrate to `P6Connector` second).
6. UI notes for `LoginRejected`/`BindRejected`.

## 9. Must not change

- `reconnect()` → LOGIN (`bindOrLogin=false`) — preserves the working P6 path.
- Never introduce `AuthMode.AUTO`.
- `AutoReconnectMode.BALANCED` stays (SDK transport recovery).
- `USER_ID = "bluebit_user"` remains the default for *new* BlueBit pairings.
- `token2_` untouched — SDK-managed.
- `P6Connector` untouched — consumed as diagnostic/alternative path.
- Sex/age/height/weight constants stay stable per device (prevents
  `newPreConnectedSignal` re-auth churn).

## 10. Open unknowns (hardware-dependent)

- Whether P6 LOGIN enforces userId==binding identity (determines how often
  `LoginRejected`→re-pair prompts appear for legacy records).
- Whether BIND overwrites existing watch binding/data (informs the
  pair-confirmation copy).
- Whether watch requires SDK post-auth init chain for stable sessions
  (only relevant if `P6Connector` is later promoted beyond diagnostics).
