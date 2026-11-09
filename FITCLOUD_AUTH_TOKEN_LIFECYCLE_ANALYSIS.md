# FitCloudPro WearKit SDK 3.0.2.4 — Authentication & Token Lifecycle Analysis

Static bytecode analysis (`javap -c -p`) of `sdk-base`/`sdk-fitcloud` 3.0.2.4
artifacts used by BlueBitApp, plus BlueBitApp source. No device contact, no
packets sent, no credentials read or printed.

Primary classes:

- `com.topstep.fitcloud.sdk.internal.e` — `FcConnector` implementation
- `com.topstep.fitcloud.sdk.v2.operation.a` — auth operation (`Fc#AuthOperation`)
- `com.topstep.fitcloud.sdk.v2.protocol.a` — packet build/parse helpers
- `com.topstep.wearkit.base.connector.AuthMode` — `BIND / LOGIN / AUTO`
- `com.topstep.wearkit.base.internal.a` — BLE `BaseConnector` state machine

---

## 1. Executive summary

- `AuthMode` has three values: `BIND`, `LOGIN`, `AUTO`. BlueBit uses BIND on
  `connect()` and LOGIN on `reconnect()` via the `bindOrLogin` boolean
  overloads — it never uses AUTO or the explicit-`AuthMode` overload.
- Authentication is a single protocol request/response exchange performed as
  the **first** `PreConnectedOperation`, i.e. it runs *after* BLE transport is
  prepared and *before* `ConnectorState.CONNECTED` is emitted.
- The "device token" stored under `token2_<mac>` is **not a server credential**:
  it is a locally generated `Long.toString(currentTimeMillis, 36)` string,
  created/refreshed on every successful auth. It is plaintext in app-private
  `SharedPreferences`. It is **not transmitted in the auth request** — the
  auth request carries only the userId (≤28 bytes) plus, for BIND only, an
  optional caller-supplied `code` string.
- LOGIN failure with result byte `1` **falls back to BIND** (via an internal
  `a$a` signal caught by `onErrorResumeNext`). BIND failure does not fall back.
  AUTO resolves to the LOGIN path (`doLoginAuth` with flag=false) — and,
  notably, does **not** get the bind-fallback because the flag differs.
- `FcAuthException implements InterruptedConnectionException`, so auth failures
  abort the connector retry loop → `DISCONNECTED` with
  `DisconnectedReason.AUTH_LOGIN_FAILED` (reason byte 1) or
  `AUTH_BIND_FAILED` (any other reason byte).
- BlueBit subscribes **only** to `observerConnectorState()`; it never reads
  `observerConnectorError()`, `getDisconnectedCause()`, or
  `getDisconnectedReason()`, so auth vs. transport failures are currently
  indistinguishable in the UI.

---

## 2. Where auth sits in the connection flow

```
FcConnector.connect(address, userId, bindOrLogin, sex, age, height, weight)
  └─ internal.e.connect(...)  (fc_e.txt ~line 1290)
       ├─ bindOrLogin==true → AuthMode.BIND else AuthMode.LOGIN   (fc_e.txt:1303-1308)
       ├─ operation.a.a(mode, code=null, userId)  → stores mode/userId; returns authChanged
       ├─ if authChanged && state >= PRE_CONNECTED → BaseConnector.newPreConnectedSignal()
       ├─ operation.r.a(sex,age,height,weight)    → userInfo changed → schedules re-push
       └─ BaseConnector.connect(address)
            └─ internal.a.b(device): CONNECTING → GATT+MTU+services+notify
                 → PRE_CONNECTED → run PreConnectedOperations in order:
                      [0] operation.a  AUTH (requestAuth/responseAuth, 15 s timeout)
                      [1] setSdkFunction no-response packet
                      [2] operation.r  setUserInfo
                      [3] features.g   config feature
                      [4] internal.b   classic bond (project 00000000527D)
                      [5] operation.c  device info
                      [6] operation.n  time sync (if autoSetTime)
                 → CONNECTED  (only if ALL succeed)
```

Auth errors propagate out of the pre-connected chain into
`internal.a$h$a` (`retryWhen`): `InterruptedConnectionException` (which
`FcAuthException` is) → no retry → `DISCONNECTED`.

---

## 3. `AuthMode.AUTO` decision tree — confirmed

`operation.a.protectedRun` (`op_a.txt:291-416`):

1. `FcSDK.CONNECT_SKIP_AUTH` → release semaphore, complete (auth skipped).
2. `FcSDK.ECG_PRODUCTION_TEST` → send `mockRequestAuth()` (`protocol.a.a()` =
   `FcProtocolPacket(3, 7, null)`), complete.
3. Mode resolution (synchronized on `this`):
   - field `b` (`firstAuth`, init `true`) → use stored field `d` (`AuthMode`,
     default `AUTO`).
   - after first auth completes, `doOnComplete` → `a(this)` clears field `e`
     (code) and flips `b=false`; on subsequent runs the mode becomes
     `d==AUTO ? AUTO : LOGIN` — i.e. **BIND is never re-selected implicitly**;
     a non-AUTO stored mode degrades to LOGIN on re-auth.
4. Dispatch via `a$c` WhenMappings (`AuthMode.ordinal → case`):

   | AuthMode | case | path invoked |
   |---|---|---|
   | `LOGIN` (ordinal 1) | 1 | `a(queue, sem, /*flag*/ true)` → `doLoginAuth` |
   | `BIND` (ordinal 2) | 2 | `a(queue, sem)` → `doBindAuth` |
   | `AUTO` (ordinal 0, default) | 0 | `a(queue, sem, /*flag*/ false)` → `doLoginAuth` |

   **AUTO always performs a LOGIN exchange first.** There is no stored-token
   precondition and no userId-based branch before the exchange — the only
   inputs are field `e` (code, usually null) and field `c` (userId).

5. Result-byte handling in `a$g` (`doLoginAuth` mapper, `op_inner.txt:23-95`):
   - `result == 0` → success: `f` set false, `g = b(deviceMac)` (read-or-create
     `token2_`), log `Auth login:%s`.
   - `result == 1`:
     - flag `true` (AuthMode.LOGIN) → throw `a$a` (plain RuntimeException)
       → caught by `a$i` `onErrorResumeNext` → **re-runs `doBindAuth`**
       (`op_inner.txt:240-251`). So LOGIN self-heals into BIND when the device
       reports "not bound" (result 1).
     - flag `false` (AuthMode.AUTO) → `FcAuthException(1)` →
       `AUTH_LOGIN_FAILED`, **no bind fallback**.
   - any other non-zero → `FcAuthException(-1)` → mapped to `AUTH_BIND_FAILED`
     by `getDisconnectedReason()` (reason byte ≠ 1).
6. `doBindAuth` mapper `a$e` (`op_inner.txt:124-201`):
   - `result == 0` → success: `f` set **true**, `g = a(deviceMac)` (**always
     mints a new** `token2_` value), log `Auth bind:%s`.
   - `result ∈ {2,3,5}` → `FcAuthException(same byte)`; other non-zero →
     `FcAuthException(-1)`. No fallback path.

**Answers to the task's AUTO questions:**

- *Does AUTO attempt LOGIN before BIND?* Only if login fails — but AUTO uses
  flag=false, so result-1 produces `FcAuthException`, **not** the `a$a`
  bind-fallback. AUTO therefore does **not** fall back to BIND on "not bound".
  (Confirmed from bytecode; counter-intuitive — flagged as a finding, not a bug
  assumption.)
- *Is a stored device token checked?** No. `token2_<mac>` is only read/written
  **after** a successful auth response; it plays no role in selecting or
  validating the auth exchange.
- *Does userId influence selection?* No — userId is only packet payload.
- *Invalid/expired token?* N/A — token is locally generated, never sent, never
  validated.
- *AUTO vs previously bound devices?* Identical code path; the watch's result
  byte is the only differentiator.

Field `f` (`isBindOrLogin()`): set `true` on BIND success, `false` on LOGIN
success (`a$g` passes `iconst_0`, `a$e` passes `iconst_1` to `a(a,Z)` which
writes `f`). Despite the name, after a successful LOGIN it returns **false**.
Hypothesis: `f` actually records "bound via BIND this session" or "first-auth
bind flag" — its exact intended semantics are unresolved; consumers should not
assume it means "authenticated".

---

## 4. BIND vs LOGIN — packet-level comparison

`protocol.a` helpers (`/tmp/protocol_a.txt:767-898`):

| | BIND | LOGIN |
|---|---|---|
| Request builder | `a(true, code, userId)` | `a(false, code, userId)` |
| Request packet | `FcProtocolPacket(3, 17, payload)` | `FcProtocolPacket(3, 19, payload)` |
| Response filter | `a(true)` → `(3, 18, null)` matched via `FcProtocolPacket.match` | `a(false)` → `(3, 20, null)` |
| Timeout | 15 s (`Single.timeout(15, SECONDS)`) | 15 s |
| Payload | 32 bytes, or `codeBytes+33` when `code` non-empty: `[userId ≤28B][len][codeBytes]` at offsets 0/32/33 | always 32-byte payload: `userId.getBytes()` truncated to 28 at offset 0, rest zero |
| Success result byte | `0` | `0` |
| Failure bytes | 2,3,5 → `FcAuthException(n)`; other → `FcAuthException(-1)` | 1 → bind-fallback (LOGIN) or `FcAuthException(1)` (AUTO); other → `FcAuthException(-1)` |
| Token on success | `a(mac)` → **new** `token2_` = base36(now) | `b(mac)` → existing `token2_` or create if absent |
| `f` flag | `true` | `false` |

Notes:

- For BIND, `code` (field `e`) is only non-null via the explicit
  `connect(address, authMode, code, userId, ...)` overload — the `code`
  occupies payload bytes 33+. BlueBit's `bindOrLogin` overloads always pass
  `code=null`, so BlueBit's BIND payload is the plain 32-byte userId form.
- `UPGRADE_PRODUCTION_TEST` fills payload[0..29) with `0xFF` — production-test
  backdoor, not set by BlueBit.
- LOGIN does **not** prove prior binding cryptographically — it sends only the
  userId; the *watch* decides via result byte (0 = accepted, 1 = not bound →
  bind fallback). Whether the watch enforces "same account that bound" is
  device-side and cannot be determined statically (hypothesis: result 1 vs 0
  encodes exactly this check).
- BIND on an already-bound device: no client-side guard observed; the watch
  decides (result byte). BlueBit comment "device data may be cleared if
  previously bound to another app" is consistent with BIND being potentially
  destructive — treat `connect()` (BIND) as binding-overwriting.

---

## 5. Token storage — confirmed mechanics

`operation.a` fields/methods (`op_a.txt:547-615`):

- `a(String key)`: sanitizes key (`replace(":", "")`), computes
  `Long.toString(System.currentTimeMillis(), 36)`, writes
  `prefs.edit().putString("token2_" + sanitized, value).apply()`, returns value.
- `b(String key)`: sanitizes, reads `prefs.getString("token2_" + sanitized, "")`;
  if null/empty → delegates to `a(key)` (creates one). Returns the value.
- Callers: BIND success → `a(deviceMacAddress)`; LOGIN success → `b(deviceMacAddress)`.
  `deviceMacAddress` comes from `FcProtocolQueue.getDeviceMacAddress()` — i.e.
  the **MAC address of the connected device**, not the userId.
- `getDeviceToken()` (`internal.e`, fc_e.txt:2607) returns field `g` — the
  in-memory copy of the token for the *current* connection only.

Storage details:

- SharedPreferences file: `"fc_internal_storage"`, or `"sp_wk_fc_sdk"` when
  `FcSDK.WEARKIT_COMPAT` is true (`internal.e` constructor, fc_e.txt:309-349).
  BlueBit does not set `WEARKIT_COMPAT` → `fc_internal_storage`.
- Key: `token2_<mac with ':' removed>` — **per-device, not per-user**. Two
  userIds on the same watch share one token slot.
- Value: base36 timestamp — plaintext, no encryption/encoding beyond base36.
  It is a *freshness marker / local nonce*, not a credential; nothing in the
  inspected code sends it to the watch (only `getDeviceToken()` exposes it;
  no other class in `sdk-fitcloud` references `token2_` or `getDeviceToken`
  besides `operation.a`/`internal.e`/`FcConnector` interface).
- Lifecycle: created on first successful auth (either mode); **overwritten on
  every BIND**; preserved on LOGIN; never deleted by `disconnect()`/`close()`
  (no removal code exists in `operation.a`; `internal.e.disconnect/close`
  delegate to `BaseConnector` which does not touch prefs — verified: no
  `token2_`/`remove`/`clear` references elsewhere in the jar).
- Survives process death and SDK re-init (same prefs file). **Does not**
  survive app uninstall (private storage). `WEARKIT_COMPAT` apps share
  `sp_wk_fc_sdk` — tokens can carry over from the official FitCloud app's data
  if that flag were enabled.

---

## 6. Failure path: `FcAuthException` → BlueBit UI

Confirmed chain:

```
operation.a auth op emits FcAuthException(reason)
  → e$d.execute: connection.operation(op).ignoreElements() errors
    (e$s retryWhen does NOT retry FcAuthException — predicate !(t instanceof FcAuthException))
  → a$g$a pre-connected Completable errors
  → internal.a$h$a retryWhen:
       instanceof InterruptedConnectionException → abort:
         y = DisconnectedCause.Interrupted(cause=FcAuthException)
         a(DISCONNECTED); Observable.error → a$j connectError → performClose
  → observerConnectorState() emits DISCONNECTED
  → observerConnectorError() emits ConnectorError (only for retriable errors;
     InterruptedConnectionException aborts BEFORE the onNext(ConnectorError)
     — see base_retry.txt: retry path emits ConnectorError at line ~152,
     abort path at ~295-349 does not)
  → internal.e.getDisconnectedReason(): Interrupted.cause instanceof
     FcAuthException → reason==1 ? AUTH_LOGIN_FAILED : AUTH_BIND_FAILED
```

BlueBit side (`AndroidFitCloudProvider.kt`):

- Subscribes only to `observerConnectorState()` → maps `DISCONNECTED` →
  `ConnectionState.Disconnected`. **Auth failure is indistinguishable from any
  other disconnect.**
- Never calls `observerConnectorError()`, `getDisconnectedCause()`, or
  `getDisconnectedReason()` — `AUTH_LOGIN_FAILED`/`AUTH_BIND_FAILED` are
  computed by the SDK but **dropped**.
- `pairedDeviceEvents` fires only on `CONNECTED` — auth failure simply never
  pairs; no error surfaced.
- `connect()`/`reconnect()` return `Result.success` as soon as the SDK accepts
  the call (fire-and-forget); auth outcome arrives only via state flow.

**Information BlueBit loses:** the `FcAuthException` reason byte
(login vs bind vs other), the `DisconnectedReason` enum, the
`ConnectorError.retryNumber`/`bleScanException`, and the distinction between
"watch rejected credentials" (terminal, user action needed) and "watch
unreachable" (auto-retry ongoing, `PRE_CONNECTING`).

---

## 7. BlueBit integration audit

| Question | Finding |
|---|---|
| Is `USER_ID = "bluebit_user"` correct? | Mechanically consistent: same ID used for BIND (`connect`) and LOGIN (`reconnect`), which the SDK requires (login validates against the binding's userId — inferred from watch-side check). Whether the *watch* accepts an arbitrary userId at BIND is device-side; the earlier successful P6 session used `26544037`, a **different** userId — if that device is still bound to `26544037`, BlueBit's BIND with `bluebit_user` may re-bind (potentially clearing data) or fail depending on watch policy. **Unresolved — needs device-side evidence.** |
| Consistent identities? | Yes within BlueBit (`USER_ID` constant for both paths). `testConnectLogin` uses `26544037` — a third identity; fine for diagnostics but could confuse binding state if mixed with real connect flows. |
| Failed BIND side effects? | BIND success **overwrites `token2_<mac>`** and (per SDK design / BlueBit comment) may clear watch data. A *failed* BIND leaves `token2_` untouched (token only written on result 0) but the watch-side binding state is unknown — hypothesis: watch may be left half-bound; only device testing can confirm. |
| SDK auth errors preserved? | No — see §6. `observerConnectorError`/`getDisconnectedReason` unused. |
| Distinguish auth vs transport failure? | Not currently. Recommendation (no code change made): subscribe to `observerConnectorError()` and/or poll `getDisconnectedReason()` on `DISCONNECTED` to surface `AUTH_LOGIN_FAILED`/`AUTH_BIND_FAILED` vs `ErrorUnknown`/`BtDisabled`. |

---

## 8. Confirmed vs. hypotheses

**Confirmed (bytecode/source):** all packet layouts, result-byte mappings,
fallback rules, token key format/location/lifecycle, state-machine gating,
BlueBit mapping and error-loss findings above.

**Hypotheses / unresolved:**

1. Watch-side semantics of result bytes (esp. LOGIN result 1 = "not bound by
   this userId"?) — inferred, not provable statically.
2. Whether `f`/`isBindOrLogin()` true-after-BIND/false-after-LOGIN is intended
   or a naming artifact.
3. Whether BIND on a bound device clears data (BlueBit comment asserts it;
   SDK performs no client-side check).
4. Whether `token2_` value is consumed by firmware-facing features outside
   `sdk-fitcloud` (e.g. ext-* AARs, DFU) — only `getDeviceToken()` exposes it;
   consumers not audited.
5. 3.0.2.4 vs 3.0.2.7 drift — libs/ contains 3.0.2.7; app pins 3.0.2.4.

## 9. Remaining research questions

1. Does the P6 currently hold a binding for userId `26544037`? If so, does
   BlueBit's `connect()` BIND with `bluebit_user` overwrite it — and is that
   the desired product behaviour vs. switching to LOGIN/AUTO?
2. Should BlueBit prefer `AuthMode.AUTO` (login-first) for `reconnect()` to
   gain the SDK's built-in bind-fallback… except AUTO demonstrably does **not**
   fall back (flag=false). Explicit `LOGIN` is actually the mode that falls
   back to BIND on result 1 — verify intended semantics before relying on it.
3. What does `getDeviceToken()` feed (if anything) — OTA? data encryption?
   Audit `ext-*` modules and DFU paths.
4. Exact meaning of bind result bytes 2/3/5 (busy? wrong code? already bound?).
5. Does `disconnect()`/`close()` on the watch side invalidate `token2_`?
   (Client never clears it; watch-side token state unknown.)
