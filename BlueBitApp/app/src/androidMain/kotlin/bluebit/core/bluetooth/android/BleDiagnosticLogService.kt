package bluebit.core.bluetooth.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import bluebit.app.MainActivity
import bluebit.app.R
import bluebit.core.bluetooth.BleLogEntry
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that persists BLE diagnostic events to timestamped .txt
 * files in the shared Downloads/BlueBit folder (MediaStore on Android 10+).
 *
 * Threading: all producer entries are posted to a [HandlerThread] where they are
 * batched and flushed to storage. This keeps Bluetooth callbacks responsive.
 */
class BleDiagnosticLogService : Service() {

    companion object {
        private const val TAG = "BlueBitBleDiagSvc"
        const val ACTION_START = "bluebit.core.bluetooth.android.ACTION_START"
        const val ACTION_STOP = "bluebit.core.bluetooth.android.ACTION_STOP"
        private const val CHANNEL_ID = "bluebit_ble_diagnostic"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024
        private const val FLUSH_INTERVAL_MS = 2000L
        private const val MAX_BATCH_ENTRIES = 100
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private var isRunning = false

    private val batch = ArrayList<BleLogEntry>()
    private var currentUri: Uri? = null
    private var currentFileName: String? = null
    private var currentSize = 0L

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("BleDiagnosticLogService").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLogging()
            ACTION_STOP -> stopLogging()
            else -> {
                Log.w(TAG, "Unexpected intent action: ${intent?.action}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLogging() {
        if (isRunning) return
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("BLE diagnostic logging active"))
        Log.i(TAG, "Foreground service started")

        BleDiagnosticLogger.setConsumer { entry ->
            handler.post { onEntry(entry) }
        }
        handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    private fun stopLogging() {
        if (!isRunning) return
        isRunning = false
        BleDiagnosticLogger.clearConsumer()
        handler.removeCallbacks(flushRunnable)
        handler.post {
            flushBatch()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private val flushRunnable: Runnable = Runnable {
        flushBatch()
        if (isRunning) {
            handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
        }
    }

    private fun onEntry(entry: BleLogEntry) {
        batch.add(entry)
        if (batch.size >= MAX_BATCH_ENTRIES) {
            flushBatch()
        }
    }

    private fun flushBatch() {
        if (batch.isEmpty()) return
        val lines = batch.map { "${it.formattedTimestamp()} [${it.level}] ${it.tag}: ${it.message}" }
        batch.clear()
        val text = lines.joinToString("\n", postfix = "\n")
        writeText(text)
    }

    private fun writeText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            ensureFile(bytes.size.toLong())
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> writeMediaStore(bytes)
                else -> writeDirectFile(bytes)
            }
            updateNotification("BLE diagnostic logging active\nFile: $currentFileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostic log", e)
            updateNotification("BLE log error: ${e.message}")
        }
    }

    private fun ensureFile(neededBytes: Long) {
        if (currentUri == null || currentSize + neededBytes > MAX_FILE_SIZE_BYTES) {
            currentUri = createNewFile()
            currentSize = 0L
        }
    }

    private fun createNewFile(): Uri? {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        currentFileName = "bluebit_ble_$timestamp.txt"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreEntry(currentFileName!!)
        } else {
            createDirectFileUri(currentFileName!!)
        }
    }

    private fun createMediaStoreEntry(fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/BlueBit")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            Log.i(TAG, "Created MediaStore log file: $fileName -> $uri")
        } else {
            Log.e(TAG, "Failed to create MediaStore entry for $fileName")
        }
        return uri
    }

    private fun writeMediaStore(bytes: ByteArray) {
        val uri = currentUri ?: throw IOException("No current MediaStore URI")
        contentResolver.openOutputStream(uri, "wa")?.use { out ->
            out.write(bytes)
            out.flush()
            currentSize += bytes.size
        } ?: throw IOException("Could not open output stream for $uri")
    }

    private fun createDirectFileUri(fileName: String): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BlueBit")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create directory ${dir.absolutePath}")
        }
        val file = File(dir, fileName)
        Log.i(TAG, "Created direct log file: ${file.absolutePath}")
        return Uri.fromFile(file)
    }

    private fun writeDirectFile(bytes: ByteArray) {
        val uri = currentUri ?: throw IOException("No current direct file URI")
        val file = uri.path?.let { File(it) } ?: throw IOException("Invalid file URI: $uri")
        file.outputStream().use { out ->
            out.write(bytes)
            out.flush()
        }
        currentSize = file.length()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE Diagnostic Logging",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while BlueBit is logging BLE diagnostics in the background"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BlueBit BLE Logger")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onDestroy() {
        isRunning = false
        BleDiagnosticLogger.clearConsumer()
        handler.removeCallbacks(flushRunnable)
        handler.post {
            flushBatch()
        }
        handlerThread.quitSafely()
        super.onDestroy()
    }
}
