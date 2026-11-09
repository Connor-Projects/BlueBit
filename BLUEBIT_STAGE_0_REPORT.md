# BlueBit — Stage 0 Report: FitCloudConnector Test Seam

Stage 0 complete. Auth/connection behaviour unchanged; seam only.

## Files changed

| File | Status | Purpose |
|---|---|---|
| `app/src/commonMain/kotlin/bluebit/fitcloud/FitCloudConnector.kt` | **new** | SDK-free interface + enums + data classes |
| `app/src/androidMain/kotlin/bluebit/fitcloud/SdkFitCloudConnector.kt` | **new** | Production adapter over `FcConnector` |
| `app/src/androidMain/kotlin/bluebit/fitcloud/AndroidFitCloudProvider.kt` | modified | Injects `FitCloudConnector`; all SDK calls routed through it |
| `app/src/androidUnitTest/kotlin/bluebit/fitcloud/FakeFitCloudConnector.kt` | **new** | Test fake (call recording + stubbed returns + state emission) |
| `app/src/androidUnitTest/kotlin/bluebit/fitcloud/SdkFitCloudConnectorTest.kt` | **new** | 10 adapter-forwarding tests |
| `app/build.gradle.kts` | modified | `androidUnitTest` deps: `kotlin("test")` + coroutines-test |

## Interface design

`FitCloudConnector` (commonMain — SDK deps are androidMain-only, so the seam
had to live in commonMain for testability) exposes exactly what
`AndroidFitCloudProvider` used:

```kotlin
val states: Flow<FitCloudConnectorState>        // was observerConnectorState()
val connectedDevice: FitCloudConnectedDevice?   // was getDevice()
fun connect(address, userId, bindOrLogin, sex, age, height, weight)
fun close()                                      // was connector.close()
fun setAutoReconnectMode(mode: FitCloudAutoReconnectMode)
fun deviceInfo(): FitCloudDeviceInfo             // was configFeature().getDeviceInfo()
suspend fun lcdShapeInfo(): FitCloudLcdShapeInfo
suspend fun dialPushInfo(): FitCloudDialPushInfo
suspend fun uiInfo(): FitCloudUiInfo
```

- `FitCloudConnectorState` mirrors the 5-value `ConnectorState` enum 1:1.
- `FitCloudAutoReconnectMode` mirrors all 4 `AutoReconnectMode` values.
- Feature-query results are flat SDK-free data classes; the provider's
  report-string formatting is unchanged (verified field-by-field against
  `FcLcdShape`/`FcDialPushInfo`/`FcUiInfo`/`FcDeviceInfo` getters).
- `bindOrLogin` semantics preserved verbatim: `true`→BIND, `false`→LOGIN.
  No `AuthMode` type crosses the seam — AUTO is not representable.

## Adapter

`SdkFitCloudConnector(sdkConnector: FcConnector)` — pure delegation:
`observerConnectorState().asFlow().map{…}`, `connect(...)` same signature,
`close()`, `setAutoReconnectMode` enum map, `getDevice()` wrapped in the same
`runCatching` the provider used, `getDeviceInfo()` exceptions propagate
(same as before — provider wraps in try/catch), `requestX().await()` moved
inside the adapter's suspend functions.

## DI / wiring

```kotlin
class AndroidFitCloudProvider(
    private val context: Context,
    private val fcSDK: FcSDK,
    private val connector: FitCloudConnector = SdkFitCloudConnector(fcSDK.connector),
)
```

- Production construction `AndroidFitCloudProvider(this, fcSDK)`
  (`MainActivity.kt:403`) unchanged — default arg wraps the **same**
  `fcSDK.connector` singleton instance.
- State collection moved from RxJava `subscribe`+`CompositeDisposable` to
  `connector.states.onEach{}.launchIn(scope)`; `dispose()` now cancels the
  scope. Emissions and mapping identical.
- `pairedDeviceEvents`, `connect`, `reconnect`, `testConnectLogin`,
  `disconnect`, and all four `testQuery*` diagnostics route through the
  interface.

## Tests & results

`./gradlew :app:testDebugUnitTest` — **10/10 pass**:

- connect forwards all 7 args verbatim (incl. `bindOrLogin` true/false)
- close delegates; setAutoReconnectMode maps all 4 enum values
- states maps all 5 SDK states 1:1
- connectedDevice null-path; deviceInfo/lcdShape/dialPush/uiInfo propagate
  SDK exceptions
- fake connector records calls and emits states

`./gradlew :app:desktopTest` — 186 tests, **4 failures, all in
`P6ConnectorTest`** (native P6 transport: SN/ack sequencing assertions).
These files were not touched by this change and the failures are unrelated
to the seam (P6Connector has no dependency on `FitCloudConnector`). The
working tree already contained unrelated uncommitted modifications before
this stage; the P6ConnectorTest failures appear pre-existing — flagged for
review, not introduced here.

## Compatibility notes

- `deviceInfo()` throws on SDK failure (preserves `testQueryDeviceInfo`
  error path); `pairedDeviceEvents` still wraps it in `runCatching` as
  before.
- `connectedDevice` keeps the provider's original `runCatching{getDevice()}`
  → null-on-failure semantics inside the adapter.
- `testQueryDeviceInfo` report now reads flat fields; output strings are
  byte-identical to before.
- `dispose()` semantics preserved (collection stops; SDK connector itself
  untouched, same as before).
- Remaining `fcSDK` use: only `FitCloudDialInspector(fcSDK)` — unchanged.

## Behaviour-unchanged confirmation

- LOGIN/BIND selection: untouched (`bindOrLogin` boolean path only; AUTO
  never referenced).
- userId/profile constants: untouched (`USER_ID`, `26544037` diagnostic,
  sex/age/height/weight literals all identical).
- SDK init (`FitCloudSdkInit.kt`), BLE transport, retry policy
  (`AutoReconnectMode.BALANCED` still set), pairing persistence: untouched.
- `P6Connector` and native package: untouched.
- Diagnostics: same calls, same order, same outputs — only routed through
  the adapter.

**Stage 0 complete. Awaiting review before Stage 1.**
