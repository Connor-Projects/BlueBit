package com.fitbit.goldengate.bindings.coap

import android.util.Log
import com.fitbit.goldengate.bindings.DataSinkDataSource
import com.fitbit.goldengate.bindings.GoldenGateNativeException
import com.fitbit.goldengate.bindings.NativeReference
import com.fitbit.goldengate.bindings.NativeReferenceCreationResult
import com.fitbit.goldengate.bindings.NativeReferenceWithCallback
import com.fitbit.goldengate.bindings.coap.data.IncomingResponse
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequest
import com.fitbit.goldengate.bindings.coap.handler.ResourceHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CoapEndpoint : NativeReferenceWithCallback {

    companion object {
        private const val TAG = "BlueBitGG_CoapEndpoint"
    }

    private var thisPointerWrapper: Long = 0
    private var dataSinkDataSource: DataSinkDataSource? = null
    private val isInitialized = AtomicBoolean(false)
    private val requestFilter = CoapGroupRequestFilter()
    private val schedulerExecutor = ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue())

    data class AddResourceHandlerResult(val resultCode: Int, val handlerNativeReference: Long)
    data class ResponseForResult(val resultCode: Int, val nativeResponseListenerReference: Long)

    init {
        val result = create()
        if (result == null || !result.isSuccess) {
            throw RuntimeException("CoapEndpoint create failed: ${result?.resultCode}")
        }
        thisPointerWrapper = result.pointer
        Log.i(TAG, "CoapEndpoint created, ptr=0x${thisPointerWrapper.toString(16)}")

        val filterResult = attachFilter(thisPointerWrapper, requestFilter.getThisPointerWrapper())
        if (filterResult < 0) {
            throw RuntimeException("CoapEndpoint attachFilter failed: $filterResult")
        }
        isInitialized.set(true)
        Log.i(TAG, "CoapEndpoint initialized")
    }

    // Native methods — MUST match Fitbit's JNI signatures exactly
    private external fun create(): NativeReferenceCreationResult?
    private external fun addResourceHandler(j: Long, str: String, resourceHandler: ResourceHandler, b: Byte): AddResourceHandlerResult
    private external fun asDataSink(j: Long): Long
    private external fun asDataSource(j: Long): Long
    private external fun attach(j: Long, j2: Long, j3: Long): Int
    private external fun attachFilter(j: Long, j2: Long): Int
    private external fun destroy(j: Long): Int
    private external fun detach(j: Long, j2: Long): Int
    private external fun removeResourceHandler(j: Long): Int
    private external fun responseFor(j: Long, outgoingRequest: OutgoingRequest, coapResponseListener: CoapResponseListener): ResponseForResult
    private external fun responseForBlockwise(j: Long, outgoingRequest: OutgoingRequest, coapResponseListener: CoapResponseListener): ResponseForResult

    override fun getThisPointer(): Long = thisPointerWrapper

    override fun getThisPointerWrapper(): Long = thisPointerWrapper

    override fun onFree() {
        Log.i(TAG, "onFree called")
        thisPointerWrapper = 0
    }

    override fun setThisPointerWrapper(j: Long) {
        thisPointerWrapper = j
    }

    fun attach(dataSinkDataSource: DataSinkDataSource) {
        val sourcePtr = dataSinkDataSource.getAsDataSourcePointer()
        val sinkPtr = dataSinkDataSource.getAsDataSinkPointer()
        if (sourcePtr == 0L || sinkPtr == 0L) {
            throw IllegalArgumentException("Invalid source/sink pointer: source=$sourcePtr sink=$sinkPtr")
        }
        val result = attach(thisPointerWrapper, sourcePtr, sinkPtr)
        if (result < 0) {
            throw RuntimeException("CoapEndpoint attach failed: $result")
        }
        this.dataSinkDataSource = dataSinkDataSource
        Log.i(TAG, "CoapEndpoint attached to stack")
    }

    fun detach() {
        val dsd = dataSinkDataSource
        if (dsd != null && thisPointerWrapper != 0L) {
            val result = detach(thisPointerWrapper, dsd.getAsDataSourcePointer())
            if (result < 0) {
                Log.w(TAG, "CoapEndpoint detach returned $result")
            }
            dataSinkDataSource = null
            Log.i(TAG, "CoapEndpoint detached from stack")
        }
    }

    suspend fun sendRequest(request: OutgoingRequest): IncomingResponse = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<IncomingResponse>()
        val listener = SingleCoapResponseListener { result ->
            result.fold(
                onSuccess = { deferred.complete(it) },
                onFailure = { deferred.completeExceptionally(it) }
            )
        }

        val forceNonBlockwise = request.getForceNonBlockwise()
        Log.i(TAG, "Sending CoAP request method=${request.getMethod()} path options=${request.getOptions().size} forceNonBlockwise=$forceNonBlockwise")

        val result = if (forceNonBlockwise) {
            responseFor(thisPointerWrapper, request, listener)
        } else {
            responseForBlockwise(thisPointerWrapper, request, listener)
        }

        if (result.resultCode < 0) {
            listener.cleanupNativeListener()
            throw RuntimeException("responseFor failed: ${result.resultCode}")
        }

        Log.i(TAG, "responseFor queued, nativeListenerRef=${result.nativeResponseListenerReference}")
        deferred.await()
    }

    fun close() {
        isInitialized.set(false)
        if (thisPointerWrapper != 0L) {
            destroy(thisPointerWrapper)
            thisPointerWrapper = 0
            Log.i(TAG, "CoapEndpoint destroyed")
        }
        requestFilter.close()
        schedulerExecutor.shutdown()
    }
}
