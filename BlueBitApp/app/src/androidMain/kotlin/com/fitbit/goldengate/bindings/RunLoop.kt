package com.fitbit.goldengate.bindings

import android.util.Log
import java.util.concurrent.CountDownLatch

class RunLoop : Thread() {

    private val latch = CountDownLatch(1)

    private external fun destroyLoopJNI(): Int
    private external fun startLoopJNI(cls: Class<RunLoop>): Int
    private external fun stopLoopJNI(): Int

    override fun run() {
        Log.i("BlueBitGG", "RunLoop starting...")
        val result = startLoopJNI(RunLoop::class.java)
        Log.i("BlueBitGG", "startLoopJNI() returned $result")
        destroyLoopJNI()
        Log.i("BlueBitGG", "RunLoop destroyed")
    }

    fun startAndWaitUntilReady() {
        start()
        waitUntilReady()
    }

    fun waitUntilReady() {
        latch.await()
    }

    fun stopLoop() {
        stopLoopJNI()
    }

    private fun onLoopCreated() {
        Log.i("BlueBitGG", "RunLoop.onLoopCreated() called")
        latch.countDown()
    }
}
