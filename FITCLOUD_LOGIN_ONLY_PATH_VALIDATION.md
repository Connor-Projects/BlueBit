# FitCloud Login-Only Path Validation — WearKit SDK 3.0.2.4 / 3.0.2.7

Static bytecode investigation. **This report corrects an error in the two
prior reports** (`FITCLOUD_AUTH_TOKEN_LIFECYCLE_ANALYSIS.md` §3 and
`BLUEBIT_FITCLOUD_AUTH_ARCHITECTURE_REVIEW.md` §1): the LOGIN↔AUTO fallback
attribution was inverted. Verified against `javap -c -p` output.

Labels: **CONFIRMED** = bytecode/source; **INFERRED** = strongly supported;
**UNKNOWN** = needs device evidence.

---

## 1. Executive conclusion

> **Can BlueBit safely perform LOGIN using the existing SDK without any
> automatic transition to BIND? — YES. CONFIRMED.**

`AuthMode.LOGIN` — reachable via the public boolean overload
`connect(address, userId, bindOrLogin=false, …)` that BlueBit's `reconnect()`
already uses — performs `doLoginAuth` with the bind-fallback flag **false**.
On watch result byte `1` it throws `FcAuthException(1)` →
`DisconnectedReason.AUTH_LOGIN_FAILED` → `DISCONNECTED`. No BIND packet is
ever sent on the LOGIN path.

The mode that *does* fall back to BIND is **`AuthMode.AUTO`** (and only it):
AUTO runs `doLoginAuth(flag=true)`; result `1` throws internal `a$a`, caught
by `onErrorResumeNext` → `doBindAuth`. AUTO is the SDK's "login, else bind"
convenience mode — the opposite of what the previous reports claimed.

**Practical consequence:** BlueBit's current split is already correct —
`connect()`→BIND (explicit pair), `reconnect()`→LOGIN (login-only, fails
safely). The hazards are elsewhere: diagnostics sharing the singleton
connector (§6), identity consistency, and retry policy — not silent re-bind
on reconnect.

---

## 2. Public API findings — CONFIRMED

`com.topstep.fitcloud.sdk.v2.FcConnector` (interface, `sdk-fitcloud`):

```java
void connect(String address, String userId, boolean bindOrLogin,
             boolean sex, int age, float height, float weight);
void connect(String address, AuthMode mode, String code, String userId,
             boolean sex, int age, float height, float weight);
void connect(BluetoothDevice device, String userId, boolean bindOrLogin, …);
void connect(RxBleDevice device, String userId, boolean bindOrLogin, …);
void disconnect(); void reconnect(); void close();
Observable<ConnectorState> observerConnectorState();
Observable<ConnectorError> observerConnectorError();
ConnectorState getConnectorState();
DisconnectedCause getDisconnectedCause();
DisconnectedReason getDisconnectedReason();
void setAutoReconnectMode(AutoReconnectMode);
void setAutoReconnectInterval(int);
void registerPreConnectedOperation(PreConnectedOperation<? super FcConnection>);
```

Implementation: `com.topstep.fitcloud.sdk.internal.e` (all `public`).

| # | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Overload accepting `AuthMode`? | **Yes** — `connect(String, AuthMode, String, String, boolean, int, float, float)`, public, on the `FcConnector` interface. Mode is passed verbatim to `operation.a.a(mode, code, userId)` (`internal.e`, bytecode ~line 1516–1520 of `connect` 9-arg body). | `FcConnector` javap; `fc_e.txt:1457+` |
| 2 | Can AUTO be selected publicly? | **Yes** — `AuthMode.AUTO` is a public enum constant (`com.topstep.wearkit.base.connector.AuthMode`, sdk-base) and the AuthMode overload stores it unmodified in `operation.a.d`. | `AuthMode` javap; `operation.a.a(AuthMode,String,String)` `op_a.txt:187–271` |
| 3 | Builder/config auth-mode setting? | **No.** `FcSDK.Builder` exposes features/lifecycle/test flags only; auth mode is per-`connect()` call. `CONNECT_SKIP_AUTH` and `ECG_PRODUCTION_TEST` companion flags bypass/mock auth (`protectedRun`, `op_a.txt:302–329`) — neither is a supported login-only route. | `protectedRun` bytecode |
| 4 | Alternative auth entry points? | `registerPreConnectedOperation` lets callers *add* ops but cannot replace/suppress `operation.a`. `reconnect()` re-runs the stored mode — no new entry point. | `FcConnector` javap |
| 5 | Flag disabling LOGIN→BIND fallback? | **Yes, implicitly: use `AuthMode.LOGIN` (or `bindOrLogin=false`).** There is no explicit "no-fallback" flag; the fallback is gated by the internal boolean described in §3. | `operation.a` dispatch |
| 6 | Supported LOGIN-without-fallback? | **Yes — `AuthMode.LOGIN` itself.** It is the non-fallback path. | §3 |

Note on the AuthMode overload's extra `String` params: order is
`(address, mode, code, userId, sex, age, height, weight)` — confirmed by the
log format `connect address1:%s userId:%s mode:%s …` where arg4 prints as
`userId` (`fc_e.txt:1513–1515`) and `operation.a.a(mode, arg3→e=code,
arg4→c=userId)`. BlueBit's boolean overload always passes `code=null`.

---

## 3. Corrected dispatch table — `operation.a.protectedRun`

`com.topstep.wearkit.base.connector.AuthMode` enum ordinals: `BIND=0`,
`LOGIN=1`, `AUTO=2` (declaration order in `AuthMode` class).

`operation.a$c` synthetic switch map (`a$c.<clinit>`):
`LOGIN.ordinal→1`, `BIND.ordinal→2`, `AUTO`→default `0`.

`protectedRun` (`op_a.txt:338–406`) resolves the effective mode under
`synchronized(this)`:

```
if (field b /*authPending*/)      effective = field d            // verbatim
else                              effective = (d == AUTO) ? AUTO : LOGIN
```

then dispatches (`op_a.txt:365–400`):

| case | mode | call | fallback wrapper |
|---|---|---|---|
| 1 | **LOGIN** | `a(queue, sem, /*flag*/ false)` → `doLoginAuth` | `doOnError` only — **NO `onErrorResumeNext`** |
| 2 | **BIND** | `a(queue, sem)` → `doBindAuth` | none |
| 0 (default) | **AUTO** | `a(queue, sem, /*flag*/ true)` → `doLoginAuth` | `doOnError` **+ `onErrorResumeNext(a$i → doBindAuth)`** |

Result-byte handling in `a$g` (login mapper, `op_inner.txt:23–95`):

- `result == 0` → success; `f=false`; `g = b(mac)` (read-or-create `token2_`).
- `result == 1`:
  - flag **true** (AUTO) → `throw new a$a()` → `a$i.a(Throwable)` sees
    `instanceof a$a` → resubscribes `doBindAuth` (`op_inner.txt:240–251`).
  - flag **false** (LOGIN) → `throw new FcAuthException((byte)1)` →
    `AUTH_LOGIN_FAILED`, connection aborts.
- other non-zero → `FcAuthException(-1)` → `AUTH_BIND_FAILED` (reason≠1).

Bind mapper `a$e`: `0`→success (`f=true`, new `token2_`); `2/3/5`→
`FcAuthException(n)`; else `FcAuthException(-1)`. No fallback.

`operation.a.a(mode, code, userId)` (`op_a.txt:187–271`) stores
`d=mode, c=userId, e=code` and recomputes `b`:
`authChanged = isBind(mode) != (b && d_prev==BIND)`; when changed → `b=true`
(next auth uses stored mode verbatim), else `b` unchanged. Net effect
(CONFIRMED by tracing): **BIND re-executes on every `connect()` call**
(`isBind=1` always differs), while LOGIN/AUTO leave `b=false` so dispatch
resolves `d==AUTO?AUTO:LOGIN` — i.e. stored BIND also degrades to LOGIN if a
later call stores LOGIN. BlueBit always re-specifies the mode per call, so
each `connect()`/`reconnect()` deterministically selects BIND or LOGIN.

---

## 4. Complete AUTO call graph — CONFIRMED

```
FcConnector.connect(..., AuthMode.AUTO, ...)          [public, 9-arg]
  └─ internal.e.connect → operation.a.a(AUTO, code, userId)  → d=AUTO, b=false
authorization each connection (every BLE connect / SDK auto-reconnect /
newPreConnectedSignal re-run of the pre-connected chain):
  operation.a.protectedRun
    ├─ CONNECT_SKIP_AUTH → complete (no auth)
    ├─ ECG_PRODUCTION_TEST → mockRequestAuth, complete
    └─ effective = AUTO (b=false, d==AUTO)
        └─ doLoginAuth(flag=true)                        [packet (3,19,payload)]
             ├─ result 0 → success (token2_ read-or-create) → CONNECTED path
             ├─ result 1 → throw a$a
             │     └─ a$i.onErrorResumeNext → doBindAuth  [(3,17,payload)]
             │           ├─ result 0 → success (new token2_) → CONNECTED
             │           └─ result 2/3/5/other → FcAuthException → AUTH_*_FAILED
             └─ result other → FcAuthException(-1) → abort
```

AUTO→BIND triggers: **every** AUTO auth where the watch returns `1` —
initial connect, every SDK auto-reconnect, every `reconnect()`, and every
`newPreConnectedSignal()`-driven re-auth (the auth op re-runs each time the
pre-connected chain executes). There is no AUTO path that avoids the
fallback; the only AUTO-safe outcome is watch result `0`.

AUTO persistence: `d` is in-memory only (field of the singleton
`operation.a`); process restart resets `d=AUTO` default anyway. AUTO never
writes mode to disk.

**AUTO is NOT a safe "login-only" choice — it is the *unsafe* one.**

---

## 5. Version 3.0.2.4 vs 3.0.2.7 — CONFIRMED (verified, not assumed)

Artifacts: `libs/sdk-fitcloud-v3.0.2.7.aar` + `sdk-base-v3.0.2.7.aar`
extracted and `javap`'d alongside 3.0.2.4.

| Aspect | 3.0.2.4 | 3.0.2.7 | Diff? |
|---|---|---|---|
| `FcConnector` method set | identical signatures | identical | none |
| `AuthMode` enum | BIND/LOGIN/AUTO | same | none |
| `a$c` switch map | LOGIN→1, BIND→2 | identical | none |
| `protectedRun` dispatch | LOGIN→doLoginAuth(false); BIND→doBindAuth; AUTO→doLoginAuth(true)+fallback | bytecode at same offsets, same structure (`op_a7.txt:371–394`) | none |
| `a$g` result-1 branch | flag?`a$a`→bind : `FcAuthException(1)` | same (`g7.txt:50–78`) | none |
| `a$i` fallback | `instanceof a$a` → doBindAuth | present | none |

**The LOGIN/AUTO/BIND semantics are unchanged between 3.0.2.4 and 3.0.2.7.**
(Other unrelated SDK internals may differ; not exhaustively diffed — only
auth-relevant classes were compared.)

---

## 6. Diagnostic & persistence isolation — CONFIRMED

`AndroidFitCloudProvider` holds a single `connector = fcSDK.connector`
singleton shared by `connect()`, `reconnect()`, and `testConnectLogin()`.

- `testConnectLogin()` calls `connector.connect(userId="26544037",
  bindOrLogin=false)` → **LOGIN mode** → on success emits
  `ConnectorState.CONNECTED` on the shared state stream.
- `pairedDeviceEvents` filters that same stream for `CONNECTED` and emits a
  `PairedDeviceRecord(providerId="fitcloud", address, name, extra={project})`
  → `PairedDeviceCoordinator.collectPairedEvents` → `store.upsert` →
  **a diagnostic session is persisted as a normal pairing.**
- The diagnostic also overwrites `operation.a` auth config
  (`d=LOGIN, c="26544037"`) — a subsequent real `connect()` restores
  `bluebit_user`/BIND, so config drift self-heals on next explicit connect,
  but a coordinator `reconnect()` firing *between* diagnostic and real
  connect would LOGIN as `26544037`.
- Shared `connectionState` flow → coordinator `observeConnectionState` sees
  diagnostic connect/disconnect as provider state (can trigger
  `onUnexpectedDisconnect` retry loops against the diagnostic session's
  managed records).
- `disconnect()`/`close()` on the shared connector kills any in-flight
  normal session — no session multiplexing exists (single connector).

**Smallest separation:** give diagnostics a *separate* `FcConnector`-equivalent
path — i.e. route `testConnectLogin` (and future diagnostics) through the
native `P6Connector`/`BleConnection` stack, or gate `pairedDeviceEvents` and
`connectionState` emission behind an `inDiagnosticSession` flag set around
diagnostic calls. The former is cleaner: diagnostics then can't touch SDK
auth state at all.

---

## 7. Native `P6Connector` completeness — CONFIRMED (source) / INFERRED (protocol)

`app/.../fitcloud/native/P6Connector.kt` + `P6AuthPackets.kt` +
`P6KeyDerivation.kt` + transport/decoder classes.

| Capability | SDK (`operation.a`+chain) | Native `P6Connector` |
|---|---|---|
| LOGIN packet | `(3,19, 32B userBlock)` via `protocol.a.a(false,…)` | `submit(userId, bind=false)` → keyId `0x13` = decimal 19 — **same** (`P6AuthPackets.kt:49–55`) |
| BIND packet | `(3,17,…)` | keyId `0x11` = 17 — same |
| userBlock | UTF-8 userId ≤28B, zero-pad 32 | `P6KeyDerivation.userBlock` — same layout |
| Result codes | 0 ok; 1 bind-required; 2/3/5 bind errors | identical constants + `describeResult` |
| LOGIN result 1 | `FcAuthException(1)` (LOGIN) / auto-BIND (AUTO) | **throws `P6ProtocolException`, never binds** — strictly safer |
| Result delivery | response packet `(3,18/20)` matched by `FcProtocolPacket.match` | submit-first + result push `(3,0x14)` — hardware-verified on P6 (comment cites 2026-11-04 capture); decompiled SDK actually waits for `(3,18/20)` response — **INFERRED discrepancy**: native relies on push semantics observed on hardware; SDK waits on a matching response frame. Both apparently work on P6, but frame-level equivalence is not byte-verified. |
| Post-auth init | setSdkFunction, `operation.r` userInfo, feature config, classic bond (`internal.b`), `operation.c` device info, `operation.n` time sync | **absent** — connector stops at `AUTHENTICATED` |
| Feature APIs | `configFeature()`, `settingsFeature()`, etc. | none — raw `sendPacket`/`events` only |
| Session crypto | AES-256-CTR util + encrypted flag mirror | `P6SessionCipher` + `inboundEncryptedMirror` implemented |
| Reconnect/auto-retry | `AutoReconnectMode`, bounded backoff | none — caller-managed |
| Token `token2_` | managed by `operation.a` | n/a (unused by auth anyway) |
| Identity | userId string only | `P6Identity(userId, macAddress)` — MAC needed for key derivation; obtained from BLE device — fine |
| State surface | 5-state `ConnectorState` + error/reason | 7-state `P6ConnectionState` + `P6SessionEvent` — richer auth granularity, no `DisconnectedReason` |

**Missing for production parity:** the entire post-auth init chain (device
may behave differently without `setSdkFunction`/time-sync/userInfo pushes —
UNKNOWN whether watch requires them), all feature operations, OTA/DFU
support, auto-reconnect, and integration into `WearableProvider`.

---

## 8. Options A–D

| | A. SDK + AUTO | B. SDK + explicit LOGIN | C. Native P6Connector for LOGIN | D. Other SDK config |
|---|---|---|---|---|
| No auto-BIND after LOGIN? | **NO** — AUTO falls back on result 1 | **YES** — CONFIRMED | YES — throws on result 1 | Only via B |
| Evidence | `protectedRun` default branch + `a$i` | dispatch case 1, flag=false | `P6Connector.authenticate` | — |
| Functionality retained | full SDK | **full SDK** | transport+auth only; loses all features/init | full SDK |
| Complexity | trivial (switch overload) | **zero — already implemented** in `reconnect()` | high — reimplement init/features or hybrid | n/a |
| Uncertainties | watch result-1 frequency | whether watch enforces userId match (UNKNOWN); BIND-overwrite semantics (UNKNOWN) | protocol completeness vs SDK init chain; frame-level differences | none found |
| Production-suitable? | **No** — unsafe | **Yes, with caveats** | Not yet — diagnostic-grade | — |

**Answer to the central question:** BlueBit *can* safely LOGIN via the
existing SDK — `bindOrLogin=false`/`AuthMode.LOGIN` is genuinely
login-only. AUTO must never be used for reconnects.

---

## 9. Recommended architecture

1. **Keep `reconnect()` → LOGIN** (already correct). Keep `connect()` → BIND
   for explicit pairing only.
2. **Never pass `AuthMode.AUTO`** — it is the fallback mode. Document this in
   code review; optionally wrap connector access so only BIND/LOGIN are
   expressible.
3. **Isolate diagnostics** from the shared `fcSDK.connector`: route
   `testConnectLogin` through `P6Connector` (or add a suppression flag around
   `pairedDeviceEvents`/`connectionState`). Prevents phantom pairing records
   and auth-config drift.
4. **Surface auth failures** via `observerConnectorError()` +
   `getDisconnectedReason()` → `AUTH_LOGIN_FAILED` → UI "re-pair required"
   instead of silent coordinator retries (which today would re-issue LOGIN —
   safe but futile — up to `maxAttempts`).
5. **Per-device userId** in `PairedDeviceRecord.extra` (per prior review);
   `bluebit_user` only for new BlueBit-initiated pairings.
6. Optional future: adopt `P6Connector` for a strict-login production path
   only after the post-auth init gap is closed or proven unnecessary on P6
   hardware.

---

## 10. Evidence index

- `sdk-fitcloud-3.0.2.4`: `com.topstep.fitcloud.sdk.v2.operation.a`
  (`protectedRun` dispatch `op_a.txt:338–406`; `a(mode,String,String)`
  `op_a.txt:187–271`), `a$c` (`a$c.<clinit>`), `a$g`/`a$e`/`a$i`
  (`op_inner.txt`), `com.topstep.fitcloud.sdk.v2.protocol.a`
  (`protocol_a.txt:767–898`), `com.topstep.fitcloud.sdk.internal.e`
  (`fc_e.txt`/`e_full.txt`), `com.topstep.fitcloud.sdk.v2.FcConnector`.
- `sdk-base`: `com.topstep.wearkit.base.connector.AuthMode` (public enum,
  3 constants).
- `sdk-fitcloud-3.0.2.7`: identical auth classes verified
  (`/tmp/fcjar7`, `op_a7.txt`, `g7.txt`).
- BlueBit: `AndroidFitCloudProvider.kt:103–176` (`connect`/`reconnect`/
  `testConnectLogin`), `:256–273` (`pairedDeviceEvents`),
  `P6Connector.kt:349–377` (`authenticate`), `P6AuthPackets.kt`,
  `P6KeyDerivation.kt`, `PairedDeviceCoordinator.kt`.

## 11. Remaining unknowns

- **UNKNOWN:** whether the P6 enforces LOGIN userId == binding userId
  (determines if `bluebit_user` LOGIN against a `26544037` binding returns
  result 1 → `AUTH_LOGIN_FAILED`, which is now known to be *safe* — it just
  fails).
- **UNKNOWN:** whether BIND overwrites watch data / re-binding semantics.
- **INFERRED:** native submit-first vs SDK response-match frame equivalence —
  hardware capture suggests both work; byte-level diff not performed.
- **UNKNOWN:** whether watch requires the SDK post-auth init chain for normal
  operation (relevant only if P6Connector is ever promoted).
