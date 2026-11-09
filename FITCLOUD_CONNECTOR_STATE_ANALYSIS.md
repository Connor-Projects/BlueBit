# FitCloudPro WearKit SDK 3.0.2.4 — `ConnectorState.CONNECTED` Analysis

Static reverse-engineering report. No device was contacted; all findings are from
bytecode (`javap -c -p`) of the SDK artifacts used by BlueBitApp
(`com.topstep.wearkit:sdk-base:3.0.2.4`, `com.topstep.wearkit:sdk-fitcloud:3.0.2.4`,
see `BlueBitApp/app/build.gradle.kts:40-43`) and from BlueBitApp source.

Note on versions: `FitCloudPro-SDK-Android/libs/` ships `sdk-*-v3.0.2.7.aar`, but
BlueBitApp pins **3.0.2.4**. The bytecode analysed here comes from the
`wearkit-base-inspect/classes.jar` / `wearkit-fitcloud-inspect/classes.jar`
extracted artifacts in `BlueBitApp/`, which correspond to the versions the app
resolves. Package/class names are identical between 3.0.2.4 and 3.0.2.7; line-level
behaviour should be re-verified if the app is ever bumped.

---

## 1. Object graph: `FcSDK.Builder` → concrete connector

```
FcSDK.Builder.build()                      // com.topstep.fitcloud.sdk.v2.FcSDK$Builder
  └─ new com.topstep.fitcloud.sdk.internal.g(builder)   // FcSDK implementation
        └─ lazy { new com.topstep.fitcloud.sdk.internal.e(...) }  // FcConnector impl (g$a)
              └─ new BaseConnector.BleBuilder(...)
                    .setMainServiceUUID(default 000001ff-3c17-d293-8e48-14fe2e4da212,
                                     or UUID_CONFIG.service)
                    .setMainCharacteristicUUID(write  ff02, notify ff03 defaults)
                    .setTagPrefix("Fc#")
                    .build()
                  └─ new com.topstep.wearkit.base.internal.a<FcConnection>(builder)
                        // BLE BaseConnector<T extends BleBaseConnection>
```

- `FcSDK.getConnector()` → `internal.g.getConnector()` → lazy `internal.e`
  (`FcConnector` implementation). `internal.e` field `i` is the
  `BaseConnector<FcConnection>`.
- `internal.e` field `h` is `internal.connection.a`, a
  `BleBaseConnection.Factory<FcConnection>`; `create(params)` produces
  `internal.connection.b extends FcConnection`.
- BLE service/characteristic defaults (constructor of `internal.e`,
  `fc_e.txt` ~lines 178-232): service `000001ff-3c17-d293-8e48-14fe2e4da212`,
  write `0000ff02-...`, notify `0000ff03-...` (custom UUIDs overridable via
  `FcSDK.UUID_CONFIG`).

## 2. Where each state is emitted

All emissions funnel through `internal.a.a(ConnectorState)` (sdk-base
`com.topstep.wearkit.base.internal.a`), which stores field `x` and calls
`Subject.onNext` only when the value **changes** (dedup, `base_a.txt:282-311`,
log tag `notifyStateChange:%s`).

Connect pipeline (`internal.a.b(RxBleDevice)`, `base_a.txt:606-737`):

```
Observable.just(device)
  .flatMap(a$f  connectStart)      -> emits CONNECTING          (a$f, base_a_f.txt:56-57)
  .flatMap(a$g  preConnectedGate)  -> emits PRE_CONNECTED, runs pre-ops
  .retryWhen(a$h  connectRetry)    -> emits PRE_CONNECTING / DISCONNECTED / ConnectorError
  .subscribe(a$i connectPrepared, a$j connectError)
                                     a$i emits CONNECTED         (a$i, base_a_i.txt:61-73)
```

| State | Emitted by | Meaning (confirmed) |
|---|---|---|
| `PRE_CONNECTING` | `internal.a$h$a` retry handler (`base_retry.txt:366-369`) | A retry is scheduled after a failed connect attempt; `nextRetryTime` (`w`) is set to `now + delay`. Also the *initial* wait state semantics: state is compared against `PRE_CONNECTING` in `setDevice`/`d()` as "attempt in progress / backing off". |
| `CONNECTING` | `internal.a$f` `connectStart` (`base_a_f.txt:44-57`) | Emitted synchronously when the RxJava connect chain starts, **before** any GATT operation. Guarded by `connectId == A`. |
| BLE transport setup | `internal.a.c(rxDirectConnect)` / `a(device,timeout)` (scan) → `rxPrepare` `a(RxBleConnection,RxBleDevice)` (`base_a.txt:852-989`) | `establishConnection` → `requestMtu(515)` → `discoverServices` → `getService(mainServiceUUID)` → `a$r` sets write characteristic + `setupNotification(notifyChar)` → `a$s` calls `BleBaseConnection.Factory.create(params)` → emits `FcConnection`. **No state is emitted for "GATT connected" itself** — there is no distinct state between CONNECTING and PRE_CONNECTED for transport readiness. |
| `PRE_CONNECTED` | `internal.a$g$a` `connectSuccess` (`base_retry.txt:92-93`) | Emitted inside `switchMap` on `newPreConnectedSignal().startWith(Unit)` **before** the registered `PreConnectedOperation`s run. Signals "BLE prepared, starting auth/init sequence". |
| `CONNECTED` | `internal.a$i` `connectPrepared` onNext (`base_a_i.txt:56-73`) | Emitted only after the `a$g` inner observable emits the `BleBaseConnection` — i.e. **after all registered `PreConnectedOperation`s have completed**. `a$i` stores it in field `B` (accessible via `getConnection()`) and resets retry counters. |

`PRE_CONNECTED`/`CONNECTED` re-fire on every `newPreConnectedSignal()`:
`internal.e.a(AuthMode, userId, code, ...)` calls `BaseConnector.newPreConnectedSignal()`
whenever stored auth params change while `state >= PRE_CONNECTED`
(`fc_e.txt:66-93`, and the duplicated blocks at ~1349, ~1555, ~1769, ~1989).
Each signal re-runs the whole pre-connected chain via `switchMap`, so observers
see `PRE_CONNECTED → CONNECTED` again (each is a real state change, so both are
re-emitted).

## 3. What `CONNECTED` actually means — confirmed

`CONNECTED` = **BLE transport prepared AND the entire FitCloud pre-connected
operation chain completed**, which includes authentication and device
initialisation. It is *not* merely "BLE link up".

Evidence chain:

1. `a$g` (`base_a_g.txt`) maps each `Unit` from
   `r.startWith(Unit).switchMap(a$g$a)`.
2. `a$g$a.a(Unit)` (`base_retry.txt:55-201`, log `connectSuccess`):
   - emits `PRE_CONNECTED` (line 92-93);
   - builds `Completable.complete().andThen(op.execute(this, conn))` for every
     `PreConnectedOperation` in list `q`, sequentially;
   - then `andThen(Observable.just(conn).mergeWith(s))` where `s` is the
     optional `setControlConnectedOperation` completable;
   - returns that Observable — the `conn` item only reaches `a$i` (→ `CONNECTED`)
     **after** the pre-op completable finishes.
3. `internal.e`'s constructor registers the pre-connected operations on the
   `BaseConnector` (`fc_e.txt:601-723`). In the normal (non-production-test)
   path the registered operations, in order, are:

   | # | Operation class | Role |
   |---|---|---|
   | 1 | `v2.operation.a` (tag `Fc#AuthOperation`) | **Auth**: `doBindAuth`/`doLoginAuth` |
   | 2 | `FcNoResponseOperation` wrapping `v2.protocol.a.b()` (`setSdkFunction()`) | SDK function flags |
   | 3 | `v2.operation.r` | `setUserInfo(sex, age, height, weight)` — has `a(ZIFF)Z`, matches `FcConnector.setUserInfo` |
   | 4 | `v2.features.g` (FcConfigFeature impl) wrapped in `e$d` | config feature init |
   | 5 | `internal.b` | classic-bond ensure via `BondProfileHelper.bindConnect` (project `00000000527D`); throws `internal.j` (`InterruptedConnectionException`) on failure |
   | 6 | `v2.features.g.b()` → `v2.operation.c` | device info / config query (`FcDeviceInfo`, `FcExtraFirmwareInfo`) |
   | 7 | `v2.operation.n` (only when `autoSetTime` enabled) | time sync |

   Each protocol op is wrapped by `internal.e$d`, whose `execute` is
   `connection.operation(op).ignoreElements()` plus `retryWhen(e$s)`
   (`e$d` javap; `e$s` retries with 3 s delay, max 3, **except** `FcAuthException`
   — predicate is `!(t instanceof FcAuthException)`).

4. Auth detail (`v2.operation.a.protectedRun`, `op_a.txt:291-416`):
   - `FcSDK.CONNECT_SKIP_AUTH` → releases semaphore, completes immediately
     (auth skipped entirely; `isBindOrLogin` stays false).
   - `ECG_PRODUCTION_TEST` → sends `mockRequestAuth()` and completes.
   - Otherwise selects path from stored `AuthMode` (`a$c` switch):
     `doBindAuth` → `protocol.a.a(true, code, userId)` = `requestAuth(true,...)`,
     waits `responseAuth(true)` with **15 s timeout**;
     `doLoginAuth` → `requestAuth(false,...)`, `responseAuth(false)`, 15 s timeout.
   - Response result byte parsed by `a$g`/`a$e` mappers
     (`doLoginAuth result:%s` / `doBindAuth result:%s`): success →
     `a$a` emitted → `a(this, true)` sets field `f` (**`isBindOrLogin`**),
     stores device token in field `g` (`Auth bind:%s`/`Auth login:%s` logs,
     token persisted as `token2_<mac>` in SharedPreferences `fc_internal_storage`
     or `sp_wk_fc_sdk` when `WEARKIT_COMPAT`).
   - Failure → `FcAuthException(reason: Byte)` — **`FcAuthException implements
     InterruptedConnectionException`** (`javap` on `exception/FcAuthException`).
5. Failure propagation: any pre-op error (including `FcAuthException`) propagates
   out of the `a$g` switchMap into `retryWhen(a$h)`:
   - `InterruptedConnectionException` (incl. `FcAuthException`,
     `internal.j` from the bond op) → **no retry**: `DisconnectedCause.Interrupted`
     stored, `DISCONNECTED` emitted (`base_retry.txt:295-349`, `a$h$a`).
   - Other errors → `ConnectorError` emitted on `u`, `nextRetryTime` set,
     `PRE_CONNECTING` emitted, `Observable.timer(delay)` retry per
     `AutoReconnectMode` backoff (`retryNumber*interval`, capped 300 s; after
     >25 retries in background → `E=true` low-power mode). Abort when
     `connectId` stale or `canRetryContinue()==false` → `DISCONNECTED`.
   - `internal.e.getDisconnectedReason()` maps
     `Interrupted.cause instanceof FcAuthException` →
     `reason==1 ? AUTH_LOGIN_FAILED : AUTH_BIND_FAILED`
     (`fc_e.txt:2307-2317`).

**Conclusion:** `ConnectorState.CONNECTED` means *authentication succeeded (or
was explicitly skipped via `CONNECT_SKIP_AUTH`/`ECG_PRODUCTION_TEST`) **and**
the SDK's init/config pre-connected operations completed*. BLE transport alone
never produces `CONNECTED`; the closest transport-only signal is implicit —
there is no public state for "GATT connected but not authenticated". If auth
fails, the observer sees `CONNECTING → PRE_CONNECTED → (ConnectorError) →
PRE_CONNECTING…` or `→ DISCONNECTED`, never `CONNECTED`.

Caveats / hypotheses (not fully proven from bytecode):
- `AuthMode.AUTO` path selection (`a$c` table) — exact fallback order
  (stored-token login vs. bind) is inferred from method names/logs, not fully
  decompiled.
- Whether *every* registered op must succeed for `CONNECTED`: yes for ops in `q`
  (sequential `andThen`); `s` (`setControlConnectedOperation`) is merged
  concurrently and does **not** gate `conn` emission — and FitCloud never calls
  `setControlConnectedOperation` (no reference in `internal.e`).
- `PRE_CONNECTED` is emitted *before* auth runs, so `PRE_CONNECTED` must not be
  treated as "authenticated".

## 4. Comparison with legacy `com.htsmart.wristband2`

`com.htsmart.wristband2` is **not** an independent stack in this workspace: it
exists only inside `FitCloudPro-SDK-Android/libs/ext/sdk-fitcloud-compat-v3.0.1-beta05.aar`,
a compatibility shim whose `WristbandManagerImpl` holds a `FcConnector` field
and delegates. Its `bean.ConnectionState` has only
`CONNECTING / CONNECTED / DISCONNECTED`, and
`WristbandManagerImpl$observerConnectionState$1` maps:

```
DISCONNECTED   -> DISCONNECTED
PRE_CONNECTING -> DISCONNECTED   // collapsed!
CONNECTING     -> CONNECTING
PRE_CONNECTED  -> CONNECTING     // collapsed!
CONNECTED      -> CONNECTED
```

(`$EnumSwitchMapping$0` ordinal table verified.) So under the old API,
`CONNECTED` had exactly the same strong meaning (post-auth + post-init), but the
intermediate granularity was lost: a consumer of `wristband2` could not
distinguish "retry backoff" from "disconnected", nor "BLE prepared / auth running"
from "still connecting". BlueBitApp does **not** reference `htsmart`/`wristband2`
anywhere in `app/src` or git history — the comparison is SDK-side only.

## 5. BlueBit mapping (`AndroidFitCloudProvider`)

`app/src/androidMain/kotlin/bluebit/fitcloud/AndroidFitCloudProvider.kt:55-75`:

```kotlin
connector.observerConnectorState().subscribe { fcState ->
    _connectionState.value = when (fcState) {
        ConnectorState.DISCONNECTED               -> ConnectionState.Disconnected
        ConnectorState.PRE_CONNECTING,
        ConnectorState.CONNECTING                 -> ConnectionState.Connecting
        ConnectorState.PRE_CONNECTED              -> ConnectionState.DiscoveringServices
        ConnectorState.CONNECTED                  -> ConnectionState.Connected
        else                                      -> ConnectionState.Disconnected
    }
}
```

- `observerConnectorState()`/`getConnectorState()` in `internal.e` are pure
  pass-throughs to `BaseConnector.observeState()/getState()`
  (`fc_e.txt:2170-2189`) — no remapping inside the SDK.
- `ConnectionState.Connected` (BlueBit) therefore already means
  *authenticated + initialised*, matching `pairedDeviceEvents` which fires on
  `ConnectorState.CONNECTED` (`AndroidFitCloudProvider.kt:256-273`) and
  `PairedDeviceCoordinator` which treats `Connected`/`Connecting` as
  "provider busy" (`PairedDeviceCoordinator.kt:143-156`).
- **Semantic mismatch to note:** `PRE_CONNECTED → ConnectionState.DiscoveringServices`
  is misleading — by `PRE_CONNECTED` time GATT service discovery is already done;
  the SDK is actually running auth/init ops. Conversely `CONNECTED` is emitted
  *after* those ops, so `DiscoveringServices` is the state that really covers
  "authentication + initialisation in progress".
- `connect()` uses `bindOrLogin = true` (BIND) and `reconnect()` uses
  `bindOrLogin = false` (LOGIN), same `USER_ID = "bluebit_user"`
  (`AndroidFitCloudProvider.kt:103-146`). SDK-side: `bindOrLogin` selects
  `AuthMode.BIND`/`LOGIN` (`fc_e.txt:1303-1308`) stored into `operation.a`;
  `setUserInfo` params are stored in `operation.r` and re-pushed if changed
  while `state >= PRE_CONNECTED` (`fc_e.txt:252-310`).

## 6. Confirmed facts vs. hypotheses

**Confirmed (bytecode):**
- Class/method names and emission sites listed in §2.
- `CONNECTED` gated on completion of all `PreConnectedOperation`s incl. auth op.
- `FcAuthException implements InterruptedConnectionException`; auth errors are
  not retried by `e$s` and abort the connect retry loop → `DISCONNECTED`.
- `observerConnectorState()` is an unmodified pass-through.
- BlueBit mapping table in §5.

**Hypotheses / unresolved:**
- Exact `AuthMode.AUTO` decision tree inside `operation.a` (which credential
  source wins when no explicit mode is passed — `connect()` overloads without
  AuthMode pass `null` code; AUTO likely uses stored `token2_*`).
- Whether auth *response content* beyond the result byte is validated
  (e.g. token freshness) — only result byte → `FcAuthException` was confirmed.
- Whether `CONNECTED` can ever be emitted with auth effectively skipped in
  normal builds: only via `FcSDK.setCONNECT_SKIP_AUTH(true)` or
  `ECG_PRODUCTION_TEST`/`UPGRADE_PRODUCTION_TEST` companion flags — BlueBitApp
  does not set these (verified `FitCloudSdkInit.kt`), so for BlueBit
  `CONNECTED` ⇒ real auth + init completed. **This is the key practical answer.**
- Precise identity of `v2.operation.n` and `v2.protocol.a.b()` packet contents
  (inferred from call sites: time-set and SDK-function advertisement).
- Behaviour differences between 3.0.2.4 (used) and 3.0.2.7 (in `libs/`) were not
  diffed.

## 7. Remaining research questions

1. Does `operation.a` in AUTO mode attempt login-with-stored-token before bind,
   and does a stale `token2_<mac>` cause `AUTH_LOGIN_FAILED` where BIND would
   succeed? (Relevant to BlueBit's BIND-vs-LOGIN choice and token lifecycle.)
2. Which exact pre-connected ops are conditional on device `FcDeviceInfo`
   (e.g. `internal.b` bond op only for project `00000000527D`)? Could a different
   project skip auth-adjacent steps?
3. On auth failure the SDK emits `DISCONNECTED` — does `observerConnectorError()`
   also surface `FcAuthException` to the app before disconnect, and does BlueBit
   surface `AUTH_LOGIN_FAILED`/`AUTH_BIND_FAILED` anywhere in its UI?
4. `newPreConnectedSignal()` re-runs *all* pre-ops (incl. auth) on auth/userInfo
   change — confirm no ops are non-idempotent (e.g. `internal.b` re-bond,
   `operation.n` time set) when CONNECTED is already established.
5. Diff `sdk-base/sdk-fitcloud` 3.0.2.4 vs 3.0.2.7 `internal.a`/`internal.e` to
   confirm identical state semantics before any SDK bump.
