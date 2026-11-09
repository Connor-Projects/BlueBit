package bluebit.fitcloud

import com.topstep.fitcloud.sdk.v2.FcConnector
import com.topstep.wearkit.base.connector.AutoReconnectMode
import com.topstep.wearkit.base.connector.ConnectorState
import io.reactivex.rxjava3.core.Observable
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Verifies [SdkFitCloudConnector] forwards every operation to the wrapped
 * WearKit `FcConnector` with identical arguments and preserves error
 * behaviour. Uses a JDK dynamic proxy so no Android runtime is needed.
 */
class SdkFitCloudConnectorTest {

    private class CallLog {
        val connects = mutableListOf<Array<Any?>>()
        var closeCalls = 0
        var autoReconnect: AutoReconnectMode? = null
        var statesObservable: Observable<ConnectorState>? = null
        var device: Any? = null
        var configFeatureError: Throwable? = null
        var settingsFeatureError: Throwable? = null
    }

    private fun fakeSdkConnector(log: CallLog): FcConnector {
        return Proxy.newProxyInstance(
            FcConnector::class.java.classLoader,
            arrayOf(FcConnector::class.java),
        ) { _, method, args ->
            when (method.name) {
                "connect" -> {
                    log.connects += args
                    null
                }
                "close" -> {
                    log.closeCalls++
                    null
                }
                "setAutoReconnectMode" -> {
                    log.autoReconnect = args?.get(0) as AutoReconnectMode
                    null
                }
                "observerConnectorState" -> log.statesObservable
                    ?: Observable.empty<ConnectorState>().also { log.statesObservable = it }
                "getDevice" -> log.device
                "configFeature" -> log.configFeatureError?.let { throw it }
                    ?: error("configFeature not stubbed in this test")
                "settingsFeature" -> log.settingsFeatureError?.let { throw it }
                    ?: error("settingsFeature not stubbed in this test")
                else -> error("unexpected call: ${method.name}")
            }
        } as FcConnector
    }

    @Test
    fun `connect forwards address userId bindOrLogin and profile verbatim`() {
        val log = CallLog()
        val connector = SdkFitCloudConnector(fakeSdkConnector(log))

        connector.connect("AA:BB:CC:DD:EE:FF", "user-1", bindOrLogin = true, sex = true, age = 30, height = 175f, weight = 70f)
        connector.connect("11:22:33:44:55:66", "user-2", bindOrLogin = false, sex = false, age = 41, height = 160.5f, weight = 55f)

        assertEquals(2, log.connects.size)
        assertEquals(
            listOf("AA:BB:CC:DD:EE:FF", "user-1", true, true, 30, 175f, 70f),
            log.connects[0].toList(),
        )
        assertEquals(
            listOf("11:22:33:44:55:66", "user-2", false, false, 41, 160.5f, 55f),
            log.connects[1].toList(),
        )
    }

    @Test
    fun `close delegates to sdk connector`() {
        val log = CallLog()
        SdkFitCloudConnector(fakeSdkConnector(log)).close()
        SdkFitCloudConnector(fakeSdkConnector(log)).close()
        assertEquals(2, log.closeCalls)
    }

    @Test
    fun `setAutoReconnectMode maps enum values`() {
        val log = CallLog()
        val connector = SdkFitCloudConnector(fakeSdkConnector(log))
        connector.setAutoReconnectMode(FitCloudAutoReconnectMode.BALANCED)
        assertEquals(AutoReconnectMode.BALANCED, log.autoReconnect)
        connector.setAutoReconnectMode(FitCloudAutoReconnectMode.NEVER)
        assertEquals(AutoReconnectMode.NEVER, log.autoReconnect)
        connector.setAutoReconnectMode(FitCloudAutoReconnectMode.LOW_LATENCY)
        assertEquals(AutoReconnectMode.LOW_LATENCY, log.autoReconnect)
        connector.setAutoReconnectMode(FitCloudAutoReconnectMode.LOW_POWER)
        assertEquals(AutoReconnectMode.LOW_POWER, log.autoReconnect)
    }

    @Test
    fun `states maps all five sdk states one to one`() = runTest {
        val log = CallLog()
        log.statesObservable = Observable.fromArray(
            ConnectorState.DISCONNECTED,
            ConnectorState.PRE_CONNECTING,
            ConnectorState.CONNECTING,
            ConnectorState.PRE_CONNECTED,
            ConnectorState.CONNECTED,
        )
        val connector = SdkFitCloudConnector(fakeSdkConnector(log))

        val mapped = withTimeout(5_000) { connector.states.toList() }

        assertEquals(
            listOf(
                FitCloudConnectorState.DISCONNECTED,
                FitCloudConnectorState.PRE_CONNECTING,
                FitCloudConnectorState.CONNECTING,
                FitCloudConnectorState.PRE_CONNECTED,
                FitCloudConnectorState.CONNECTED,
            ),
            mapped,
        )
    }

    @Test
    fun `connectedDevice is null when sdk has no device`() {
        val log = CallLog()
        log.device = null
        assertNull(SdkFitCloudConnector(fakeSdkConnector(log)).connectedDevice)
    }

    @Test
    fun `deviceInfo propagates sdk failure`() {
        val log = CallLog()
        log.configFeatureError = IllegalStateException("no session")
        assertFailsWith<IllegalStateException> {
            SdkFitCloudConnector(fakeSdkConnector(log)).deviceInfo()
        }
    }

    @Test
    fun `lcdShapeInfo propagates sdk failure`() = runTest {
        val log = CallLog()
        log.settingsFeatureError = IllegalStateException("not connected")
        assertFailsWith<IllegalStateException> {
            SdkFitCloudConnector(fakeSdkConnector(log)).lcdShapeInfo()
        }
    }

    @Test
    fun `dialPushInfo propagates sdk failure`() = runTest {
        val log = CallLog()
        log.settingsFeatureError = IllegalStateException("not connected")
        assertFailsWith<IllegalStateException> {
            SdkFitCloudConnector(fakeSdkConnector(log)).dialPushInfo()
        }
    }

    @Test
    fun `uiInfo propagates sdk failure`() = runTest {
        val log = CallLog()
        log.settingsFeatureError = IllegalStateException("not connected")
        assertFailsWith<IllegalStateException> {
            SdkFitCloudConnector(fakeSdkConnector(log)).uiInfo()
        }
    }

    @Test
    fun `fake connector records connect calls for provider tests`() = runTest {
        val fake = FakeFitCloudConnector()
        fake.connect("AA:BB", "uid", bindOrLogin = false, sex = true, age = 1, height = 1f, weight = 1f)
        assertEquals(1, fake.connectCalls.size)
        assertEquals("uid", fake.connectCalls[0].userId)
        assertEquals(false, fake.connectCalls[0].bindOrLogin)
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            fake.states.filter { it == FitCloudConnectorState.CONNECTED }.first()
        }
        fake.emitState(FitCloudConnectorState.CONNECTED)
        assertEquals(FitCloudConnectorState.CONNECTED, withTimeout(5_000) { deferred.await() })
    }
}
