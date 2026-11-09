# BlueBit — FitCloud Implementation Patch Specification

Implementation-ready spec derived from `BLUEBIT_FITCLOUD_IMPLEMENTATION_PLAN.md`
and `FITCLOUD_LOGIN_ONLY_PATH_VALIDATION.md`. Every item verified against
current source. No code changed. Labels: **CONFIRMED** / **INFERRED** /
**UNKNOWN**.

SDK auth semantics (bytecode-verified, both 3.0.2.4 and 3.0.2.7):

| `AuthMode` | Path | Watch result `1` |
|---|---|---|
| `LOGIN` | `doLoginAuth(flag=false)` | `FcAuthException(1)` → `AUTH_LOGIN_FAILED`, terminal, **no BIND** |
| `BIND` | `doBindAuth` | `FcAuthException(n)` → `AUTH_BIND_FAILED` |
| `AUTO` | `doLoginAuth(flag=true)` + `onErrorResumeNext` | internal `a$a` → **`doBindAuth`** — never use |

---

## 1. Change-by-change verification

### 1.1 `WearableProvider.connectForRecord` — NEW default method

- **File:** `app/src/commonMain/kotlin/bluebit/core/provider/WearableProvider.kt`
- **Current:** interface has `connect`, `reconnect` (default → `connect`),
  `disconnect`, `connectionState`, `pairedDeviceEvents`. No record-aware hook.
- **Required:** add
  ```kotlin
  suspend fun connectForRecord(
      device: BleDevice, connection: BleConnection,
      record: PairedDeviceRecord?,
  ): Result<Unit> = connect(device, connection)
  ```
- **Dependencies/callers:** only caller will be `BlueBitAppShell`
  (`onToggleConnection`, ~line 232). `FitbitProvider` inherits the default —
  **no regression** (CONFIRMED default delegates to existing `connect`).
- **Regression risk:** none; additive.

### 1.2 `AndroidFitCloudProvider` routing + mutex + errors + identity

- **File:** `app/src/androidMain/kotlin/bluebit/fitcloud/AndroidFitCloudProvider.kt`
- **Current (CONFIRMED lines):**
  - `connect()` `:103–123` → always `bindOrLogin=true`, `userId=USER_ID`.
  - `reconnect()` `:125–146` → `bindOrLogin=false`, `userId=USER_ID`.
  - `testConnectLogin()` `:157–176` → LOGIN as `26544037`, same connector.
  - `pairedDeviceEvents` `:256–273` → emits on every `CONNECTED`; `extra`
    currently only `project`.
  - `disconnect()` `:275–280` → `connector.close()`.
  - init `:55–75` → subscribes `observerConnectorState()` only;
    `setAutoReconnectMode(BALANCED)`.
- **Required:**
  1. `private val connectorMutex = Mutex()`; wrap bodies of
     `connect`/`reconnect`/`disconnect`/`testConnectLogin`/`connectForRecord`
     `connector.*` invocations in `connectorMutex.withLock { }`.
     The SDK `connect()` call is fire-and-forget (returns `void`
     immediately — CONFIRMED `internal.e.connect` ends with
     `BaseConnector.connect(address)` + `Aes256CtrUtil.setEncryptedKeyAndIv`),
     so the mutex serializes *initiation only*, never the connection
     lifetime. **No deadlock**: nothing under the lock suspends on connector
     state; state callbacks only read SDK getters.
  2. `private var lastAuthUserId: String?` set under the mutex in
     `connect`/`reconnect`/`connectForRecord`; `pairedDeviceEvents` writes
     `extra["fitcloud_user_id"] = lastAuthUserId` (fallback `USER_ID`).
  3. `override suspend fun connectForRecord(device, connection, record)`:
     ```kotlin
     val userId = record?.extra?.[EXTRA_USER_ID] ?: USER_ID
     connector.connect(device.address, userId,
         bindOrLogin = record == null,   // BIND iff unpaired
         sex = true, age = 30, height = 175f, weight = 70f)
     ```
     `reconnect(device)` becomes `connectForRecord(device, recordFor(device))`
     — see §1.3 for record lookup; as an interim step it may keep using
     `USER_ID` until §1.3 lands.
  4. Error sampling: inside the existing `observerConnectorState()`
     subscriber, on `ConnectorState.DISCONNECTED` call
     `connector.disconnectedReason`/`disconnectedCause` → emit
     `errorEvents` (§1.4).
- **Dependencies:** `PairedDeviceRecord.extra` already `Map<String,String>`
  (CONFIRMED `PairedDeviceRecord.kt`); `SharedPreferencesPairedDeviceStore`
  persists `extra` generically — **no store change**.
- **Regressions:** `disconnect()` taking mutex is safe (non-blocking call).
  `testConnectLogin` under mutex prevents overlap but does NOT prevent
  pairing-record pollution — needs §1.5.

### 1.3 Record lookup for reconnect/connect routing

- **File:** `app/src/commonMain/kotlin/bluebit/core/pairing/PairedDeviceCoordinator.kt`
- **Current:** `managed` map is private; `store` private. UI has no access to
  records.
- **Required:** add
  ```kotlin
  suspend fun pairedRecord(providerId: String, address: String): PairedDeviceRecord? =
      store.load().firstOrNull { it.providerId == providerId && it.address == address }
  ```
  (or cache from `managed`; `load()` is cheap SharedPreferences JSON —
  CONFIRMED `SharedPreferencesPairedDeviceStore.load` parses on each call).
- **UI wiring:** `BlueBitAppShell.onToggleConnection` (~`:218–232`) —
  before `p.connect`, fetch record via coordinator; pass to
  `p.connectForRecord`. For `record == null` show pair-confirmation dialog
  (new UI string; reuse existing dialog components — `PairingDialog.kt`
  exists in `core/ui/components`).
- **Regression:** `restoreOnLaunch`/`retryLoop` keep calling
  `provider.reconnect` — must be updated to look up the record so LOGIN uses
  the stored `fitcloud_user_id` (see §6 migration).

### 1.4 Error propagation — timing verified

- **CONFIRMED ordering** (`base_retry.txt:295–349`): on abort, the SDK writes
  `putfield y (DisconnectedCause)` at bytecode 312 **before** emitting
  `ConnectorState.DISCONNECTED` via `a(ConnectorState)` → `Subject.onNext`
  at 338–343. Therefore reading `getDisconnectedCause()`/
  `getDisconnectedReason()` **inside the state subscriber upon DISCONNECTED**
  is race-free — the cause is already stored. `y` is `volatile` and persists
  until the next abort overwrites it; retriable failures emit
  `ConnectorError` (via `observeError`) and never reach DISCONNECTED, so no
  overwrite can occur between emission and read.
- **`observerConnectorError()`** emits `ConnectorError` only on the
  *retriable* path — auth failures (`InterruptedConnectionException`) abort
  **without** an error onNext (CONFIRMED). Do not rely on it for terminal
  classification.
- **New common type** — `app/src/commonMain/kotlin/bluebit/core/provider/ProviderError.kt` (new file):
  ```kotlin
  sealed interface ProviderError {
      val providerId: String
      data class AuthRejected(val kind: AuthRejection, val providerId: String) : ProviderError
      data class Transport(val providerId: String, val detail: String?) : ProviderError
      data class Retrying(val providerId: String, val attempt: Int) : ProviderError
      data class BluetoothDisabled(val providerId: String) : ProviderError
  }
  enum class AuthRejection { LOGIN_FAILED, BIND_FAILED }
  ```
- **`WearableProvider` addition:**
  `val errorEvents: Flow<ProviderError> get() = emptyFlow()`
- **Provider mapping** (pure, unit-testable — extract to internal fun):
  ```kotlin
  fun mapDisconnect(reason: DisconnectedReason?, cause: DisconnectedCause?): ProviderError = when {
      reason == AUTH_LOGIN_FAILED -> AuthRejected(LOGIN_FAILED, id)
      reason == AUTH_BIND_FAILED  -> AuthRejected(BIND_FAILED, id)
      cause is DisconnectedCause.BtDisabled -> BluetoothDisabled(id)
      else -> Transport(id, cause?.toString())
  }
  ```
  `Retrying` emitted from `observerConnectorError()` (`error.retryNumber`).
- **Coordinator consumption:** `PairedDeviceCoordinator.start()` launches a
  third collector per provider: `provider.errorEvents.collect { … }`;
  on `AuthRejected`/`BluetoothDisabled` → remove key from `managed` retry
  eligibility (add `authBlockedKeys` set checked in `onUnexpectedDisconnect`
  and `retryLoop`). Transport errors leave existing behaviour.

### 1.5 Diagnostic isolation — decision

**Option A (recommended, smallest): gate the existing SDK diagnostic.**

```kotlin
private val diagnosticSession = AtomicBoolean(false)

suspend fun testConnectLogin(address: String): Result<Unit> =
    connectorMutex.withLock {
        diagnosticSession.set(true)
        try { connector.connect(address, OFFICIAL_USER_ID, bindOrLogin = false, …) }
        finally { diagnosticSession.set(false) }
    }
```

- `pairedDeviceEvents`: add `.filter { it == CONNECTED && !diagnosticSession.get() }`
- `connectionState` mapping subscriber: skip `_connectionState.value =`
  writes while flag set (prevents coordinator `observeConnectionState`
  seeing diagnostic transitions).
- Residual (INFERRED, small): diagnostic still mutates `operation.a`
  auth config (`d=LOGIN, c=26544037`); next production `connect()`/
  `reconnect()` overwrites it before auth runs (CONFIRMED `connect()` always
  calls `operation.a.a(mode,…)` first), so no persistent drift — but a
  coordinator `reconnect()` *between* diagnostic call and next explicit
  connect would LOGIN as `26544037`. Mitigate: while `diagnosticSession` is
  set OR within N seconds after, coordinator retries are already serialized
  by mutex — residual window is the async auth window (~seconds). Acceptable
  for a dev diagnostic; document it.

**Option B (cleaner, more work): route `fc_connect` diagnostic through
`P6NativeDiagnostic.runLoginHandshake`** (`P6NativeDiagnostic.kt:71`,
already hardware-verified LOGIN with `VERIFIED_USER_ID=26544037`, returns
human-readable handshake report). Trade-off (CONFIRMED): loses coverage of
the *SDK* path end-to-end (init chain, features, CONNECTED semantics) —
`testQueryDeviceInfo`/`testQueryLcdShape`/`testQueryDialInfo`/`testQueryUiInfo`
(`:181–249`) require an SDK `CONNECTED` session via `configFeature()`/
`settingsFeature()` and would have nothing to query unless a production
session is live. **Recommendation:** implement Option A now; optionally add
Option B as an additional diagnostic action later. Do NOT remove the SDK
diagnostic until feature-query diagnostics have an alternative session
source.

### 1.6 Identity persistence & migration

- `pairedDeviceEvents` writes `extra = {"project":…, "fitcloud_user_id": lastAuthUserId, "fitcloud_auth_mode": "BIND"|"LOGIN"}`.
- **Migration table** (record lookup result → action):

| Record state | Action |
|---|---|
| No record | Unpaired; BIND only via confirmed pair action |
| Record, `fitcloud_user_id` present | LOGIN with that userId |
| Record, no `fitcloud_user_id` (legacy) | LOGIN with `USER_ID`; on `AuthRejected(LOGIN_FAILED)` → mark `recoveryRequired`, prompt re-pair; **never auto-BIND** |
| Record, unknown/invalid `fitcloud_auth_mode` | ignore the key; userId still honored |
| Record from diagnostic session (no `fitcloud_auth_mode`, project only) | indistinguishable from legacy — treated as legacy; self-corrects on LOGIN failure → re-pair |
| `AuthRejected` on LOGIN | `recoveryRequired` UI state; retries suppressed (§1.4); re-pair = explicit BIND only |

- **Never** write `token2_*`, never copy SDK prefs, never auto-replace
  `fitcloud_user_id` once set (only rewrite on a *successful* explicit BIND
  pairing event — which `pairedDeviceEvents` already guarantees since it
  fires only on CONNECTED).

---

## 2. Mutex & lifecycle safety — detailed

Operations and concurrency:

| Operation | Blocking? | Mutex? | Notes |
|---|---|---|---|
| `connect`/`connectForRecord`/`reconnect` | returns after SDK accepts call | yes | fire-and-forget; auth happens async inside SDK |
| `disconnect` (`connector.close()`) | quick | yes | kills in-flight attempt; safe |
| `testConnectLogin` | quick | yes + diagnostic flag | gated emissions |
| SDK auto-reconnect | internal | n/a | does not call provider; cannot deadlock mutex |
| `observerConnectorState`/`Error` callbacks | read-only SDK getters | no | never call back into mutex-protected methods |
| `pairedDeviceEvents` collector | flow | no | emits record only |

- Mutex must **not** wrap state-flow collection or `pairedDeviceEvents`.
- Cancellation: `withLock` is cancellable; if a caller is cancelled while
  waiting, no SDK call was made — safe. Once inside, SDK calls are
  synchronous/non-suspending → no cancellation mid-call.
- Re-entrancy: none of the locked bodies invoke other locked methods.
- Concurrent-allowed: multiple *observers*; one connector op at a time.
  Two different devices cannot be connected simultaneously regardless
  (single `FcConnector`) — `connectForRecord` for device B while A is
  CONNECTED will make the SDK drop A (CONFIRMED single-connector design);
  UI should also disable Connect for other devices while one is CONNECTED
  (INFERRED desirable; not strictly required for safety).

## 3. Files to change (final list)

| File | Change |
|---|---|
| `core/provider/WearableProvider.kt` | `connectForRecord` default; `errorEvents` default |
| `core/provider/ProviderError.kt` **(new)** | sealed error types |
| `fitcloud/AndroidFitCloudProvider.kt` | mutex; routing; userId persistence; error sampling; diagnostic flag |
| `core/pairing/PairedDeviceCoordinator.kt` | `pairedRecord()`; `errorEvents` collector; auth-failure retry suppression; record-aware `reconnect` |
| `BlueBitAppShell.kt` | fetch record → `connectForRecord`; pair-confirmation for unpaired; `LoginRejected`→re-pair prompt |
| `ConnectionPresentation.kt` | notes for auth rejection states |
| `MainActivity.kt` | none required for Option A (diagnostic gate is inside provider) |

Unchanged: `FitCloudSdkInit.kt`, `SharedPreferencesPairedDeviceStore.kt`,
`PairedDeviceRecord.kt` (extra map suffices), `P6Connector.kt` & native
package, SDK dependencies.

## 4. Implementation sequence

1. `ProviderError` types + `WearableProvider` defaults + provider mutex &
   error mapping (no behaviour change to auth modes).
2. `connectForRecord` + provider routing + `lastAuthUserId` persistence.
3. Coordinator `pairedRecord()` + record-aware reconnect + errorEvents
   gating.
4. UI: record lookup, pair confirmation, rejection notes.
5. Diagnostic gate (`diagnosticSession` flag).
6. Tests (§5) → device validation.

## 5. Test specification

Mocks/fakes needed: fake `FcConnector`-like seam. **Gap (CONFIRMED):**
`AndroidFitCloudProvider` takes concrete `FcSDK` — `fcSDK.connector` is a
val on a final SDK class; cannot be mocked without a wrapper. Required
refactor: introduce `FitCloudConnector` interface (or lambda/typealias seam)
implemented by an adapter over `FcSDK.connector`, injected into the
provider. This is a prerequisite for all provider unit tests.

Coordinator tests can fake `WearableProvider` directly (interface already
mockable).

| # | Test | Given/When/Then |
|---|---|---|
| T1 | Paired device uses LOGIN | record w/ userId → `connectForRecord` → connector.connect(userId=stored, bindOrLogin=false). Assert no BIND-mode call. |
| T2 | New device BINDs only on confirm | record=null → UI shows confirm; on confirm → bindOrLogin=true; on cancel → no connector call. |
| T3 | LOGIN rejection never BINDs | fake connector emits DISCONNECTED + reason AUTH_LOGIN_FAILED → assert no subsequent connect call; coordinator starts no retryLoop; errorEvents emits AuthRejected(LOGIN_FAILED). |
| T4 | Diagnostic isolation | during `testConnectLogin`, CONNECTED emission → no pairedDeviceEvents record, no connectionState write, no coordinator managed-record change. |
| T5 | Mutex serialization | launch connect+reconnect+disconnect concurrently → connector fake records strictly sequential calls, no overlap. |
| T6 | Auth failure vs transport retry | AuthRejected → retryLoop absent; Transport error after Connected→Disconnected → retryLoop ≤3 attempts w/ backoff. |
| T7 | P6 LOGIN regression (device/manual) | official-identity LOGIN reconnect still reaches CONNECTED — preserve existing path; automated: verify reconnect() issues bindOrLogin=false + USER_ID fallback for legacy record. |
| T8 | Legacy record migration | record w/o userId → reconnect uses USER_ID + LOGIN; on LOGIN_FAILED → recoveryRequired, no BIND issued. |
| T9 | Cancellation | coroutine cancelled while waiting on mutex → no connector call made. |
| T10 | Repeated taps | double-tap Connect → single connector.connect call (mutex + isProcessing). |
| T11 | Bluetooth disabled | cause BtDisabled → errorEvents BluetoothDisabled; retries paused. |
| T12 | Concurrent restore + manual connect | restoreOnLaunch + user tap → serialized; second connect sees first's state. |

## 6. Requirements needing more evidence before implementation

- **UNKNOWN:** exact `DisconnectedReason` enum constant names for
  BT-disabled/timeout in sdk-base 3.0.2.4 — mapping table must be written
  against the real enum (inspect `com.topstep.wearkit.base.connector.
  DisconnectedReason` javap during implementation).
- **UNKNOWN:** whether `getDisconnectedReason()` distinguishes
  `AUTH_LOGIN_FAILED` vs `AUTH_BIND_FAILED` purely by `FcAuthException`
  reason byte — CONFIRMED mapping exists (`internal.e`), but enum names
  need verification.
- **UNKNOWN (hardware):** P6 userId-enforcement on LOGIN; BIND data-loss
  semantics — affects only UX copy, not safety of this patch.
- **INFERRED:** `P6Connector` cannot fully replace SDK diagnostics without
  losing feature-query coverage — Option A chosen accordingly.
