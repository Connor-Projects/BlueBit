package com.fitbit.goldengate.bindings

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class GoldenGate {

    companion object {
        private const val TAG = "BlueBitGG"
        private val initialized = AtomicBoolean(false)
        private var ggLoop: RunLoop? = null

        init {
            try {
                System.loadLibrary("xp")
                Log.i(TAG, "Loaded libxp.so")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load libxp.so", e)
            }
        }

        @JvmStatic
        private external fun getVersionJNI(cls: Class<Version>): Version

        @JvmStatic
        private external fun initModulesJNI(): Int

        @JvmStatic
        private external fun pingJNI(): Int

        @JvmStatic
        private external fun registerLoggerJNI(): Int

        fun check() {
            if (!initialized.get()) {
                throw IllegalStateException("You must call GoldenGate.init() before calling this method")
            }
        }

        fun init() {
            if (initialized.getAndSet(true)) {
                Log.i(TAG, "GoldenGate already initialized")
                return
            }

            Log.i(TAG, "GoldenGate.init() starting...")

            val modulesResult = initModulesJNI()
            Log.i(TAG, "initModulesJNI() returned $modulesResult")
            if (modulesResult != 0) {
                Log.e(TAG, "Failed to initialize GoldenGate modules: $modulesResult")
                initialized.set(false)
                return
            }

            val loggerResult = registerLoggerJNI()
            Log.i(TAG, "registerLoggerJNI() returned $loggerResult")
            if (loggerResult != 0) {
                Log.e(TAG, "Failed to register logger: $loggerResult")
                initialized.set(false)
                return
            }

            val runLoop = RunLoop()
            runLoop.startAndWaitUntilReady()
            ggLoop = runLoop
            Log.i(TAG, "GoldenGate.init() complete, RunLoop started")
        }

        fun getVersion(): String {
            return try {
                getVersionJNI(Version::class.java).toString()
            } catch (e: Throwable) {
                "unknown"
            }
        }

        fun stopRunLoop() {
            try {
                ggLoop?.stopLoop()
                ggLoop = null
                Log.i(TAG, "RunLoop stopped")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to stop RunLoop", e)
            }
        }
    }

    class Version(
        val maj: Long,
        val min: Long,
        val patch: Long,
        val commitCount: Int,
        val commitHash: String,
        val branchName: String,
        val buildDate: String,
        val buildTime: String
    ) {
        override fun toString(): String = "$maj.$min.$patch-$commitCount-$commitHash"
    }
}
