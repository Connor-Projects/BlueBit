package bluebit.core.bluetooth.android

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import bluebit.core.bluetooth.BleLogEntry
import bluebit.core.bluetooth.BleLogLevel
import bluebit.core.bluetooth.BleLogTag
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory buffer and coordinator for background BLE diagnostic logging.
 *
 * Producers (scanner, connection, UI) call [log] regardless of whether logging is
 * running. When the foreground service starts it registers a consumer that drains
 * the rolling buffer and receives new entries. All file I/O happens inside the
 * service on a dedicated thread.
 */
object BleDiagnosticLogger {

    private const val TAG = "BlueBitBleDiag"

    /** Maximum entries to keep in memory before the service attaches. */
    private const val MAX_BUFFERED = 1000

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Bounded queue that drops oldest entries under pressure so the app never
     * grows unbounded memory while logging is disabled.
     */
    private val buffer = ArrayBlockingQueue<BleLogEntry>(MAX_BUFFERED)

    @Volatile
    private var consumer: ((BleLogEntry) -> Unit)? = null

    @JvmStatic
    fun log(level: BleLogLevel, tag: BleLogTag, message: String) {
        val entry = BleLogEntry(System.currentTimeMillis(), level, tag, message)
        offer(entry)
    }

    @JvmStatic
    fun log(entry: BleLogEntry) {
        offer(entry)
    }

    private fun offer(entry: BleLogEntry) {
        // Drop oldest until there is room; if a consumer is attached it receives
        // the entry immediately as well.
        while (!buffer.offer(entry)) {
            buffer.poll()
        }
        consumer?.invoke(entry)
    }

    /**
     * Registers [consumer] and drains any entries that accumulated before the
     * service was ready. Called from [BleDiagnosticLogService].
     */
    fun setConsumer(consumer: (BleLogEntry) -> Unit) {
        this.consumer = consumer
        drain(consumer)
    }

    fun clearConsumer() {
        consumer = null
    }

    private fun drain(consumer: (BleLogEntry) -> Unit) {
        while (true) {
            val entry = buffer.poll() ?: break
            consumer(entry)
        }
    }

    fun start(context: Context) {
        if (_isRunning.value) return
        _isRunning.value = true
        Log.i(TAG, "START diagnostic logging requested")
        val intent = Intent(context, BleDiagnosticLogService::class.java).apply {
            action = BleDiagnosticLogService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        if (!_isRunning.value) return
        _isRunning.value = false
        Log.i(TAG, "STOP diagnostic logging requested")
        val intent = Intent(context, BleDiagnosticLogService::class.java).apply {
            action = BleDiagnosticLogService.ACTION_STOP
        }
        context.startService(intent)
    }
}
