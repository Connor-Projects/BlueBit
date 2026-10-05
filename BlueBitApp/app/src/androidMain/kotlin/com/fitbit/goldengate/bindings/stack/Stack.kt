package com.fitbit.goldengate.bindings.stack

import android.util.Log
import com.fitbit.goldengate.bindings.DataSinkDataSource
import com.fitbit.goldengate.bindings.NativeReference
import com.fitbit.goldengate.bindings.NativeReferenceCreationResult
import com.fitbit.goldengate.bindings.dtls.TlsKeyResolver
import com.fitbit.goldengate.bindings.dtls.TlsKeyResolverRegistry
import com.fitbit.goldengate.bindings.node.NodeKey
import java.net.Inet4Address

class Stack(
    nodeKey: NodeKey<*>,
    private val transportSinkPtr: Long,
    private val transportSourcePtr: Long,
    stackConfig: StackConfig,
    isNode: Boolean
) : NativeReference, DataSinkDataSource {

    companion object {
        private const val TAG = "BlueBitGG_Stack"
    }

    private val _thisPointer: Long
    private var stackStarted = false
    private var stackClosed = false

    init {
        val result = create(
            nodeKey = nodeKey,
            configDescriptor = stackConfig.configDescriptor,
            isNode = isNode,
            transportSinkPtr = transportSinkPtr,
            transportSourcePtr = transportSourcePtr,
            localAddress = stackConfig.localAddress,
            localPort = stackConfig.localPort,
            remoteAddress = stackConfig.remoteAddress,
            remotePort = stackConfig.remotePort,
            tlsKeyResolver = TlsKeyResolverRegistry.getResolvers(),
            gattlinkRxWindowSize = stackConfig.gattlinkRxWindowSize ?: 0,
            gattlinkTxWindowSize = stackConfig.gattlinkTxWindowSize ?: 0,
            gattlinkBufferThresholdHysteresis = stackConfig.gattlinkBufferThresholdHysteresis ?: 0.0,
            gattlinkExpectedAckTimeout = stackConfig.gattlinkExpectedAckTimeout ?: 0
        )
        if (result == null || !result.isSuccess) {
            throw RuntimeException("Stack creation failed: ${result?.resultCode}")
        }
        _thisPointer = result.pointer
        Log.i(TAG, "Stack created, pointer=0x${_thisPointer.toString(16)}")

        val attachResult = attachEventListener(Stack::class.java, _thisPointer)
        Log.i(TAG, "attachEventListener returned $attachResult")
    }

    private external fun create(
        nodeKey: NodeKey<*>,
        configDescriptor: String,
        isNode: Boolean,
        transportSinkPtr: Long,
        transportSourcePtr: Long,
        localAddress: Inet4Address,
        localPort: Int,
        remoteAddress: Inet4Address,
        remotePort: Int,
        tlsKeyResolver: TlsKeyResolver?,
        gattlinkRxWindowSize: Int,
        gattlinkTxWindowSize: Int,
        gattlinkBufferThresholdHysteresis: Double,
        gattlinkExpectedAckTimeout: Int
    ): NativeReferenceCreationResult?

    private external fun attachEventListener(cls: Class<Stack>, ptr: Long): Int
    private external fun destroy(ptr: Long): Int
    private external fun getTopPortAsDataSink(ptr: Long): Long
    private external fun getTopPortAsDataSource(ptr: Long): Long
    private external fun start(ptr: Long): Int
    private external fun updateMtu(mtu: Int, ptr: Long): Int

    override fun getThisPointer(): Long = _thisPointer

    override fun getAsDataSourcePointer(): Long = getTopPortAsDataSource(_thisPointer)

    override fun getAsDataSinkPointer(): Long = getTopPortAsDataSink(_thisPointer)

    fun start() {
        if (stackStarted) return
        Log.i(TAG, "Stack.start() calling native...")
        val result = start(_thisPointer)
        Log.i(TAG, "Stack.start() returned $result")
        stackStarted = true
    }

    fun updateMtu(mtu: Int): Boolean {
        if (stackClosed) return false
        val result = updateMtu(mtu - 3, _thisPointer)
        Log.i(TAG, "updateMtu($mtu) returned $result")
        return result == 0
    }

    @Suppress("unused")
    fun onStackEvent(eventType: Int, eventCode: Int) {
        Log.i(TAG, "onStackEvent type=$eventType code=$eventCode")
    }

    @Suppress("unused")
    fun onDtlsStatusChange(state: Int, code: Int, pskIdentity: ByteArray?) {
        val pskStr = pskIdentity?.toString(Charsets.UTF_8) ?: "null"
        val pskHex = pskIdentity?.joinToString("") { "%02X".format(it) } ?: "null"
        Log.i(TAG, "onDtlsStatusChange state=$state code=$code psk='$pskStr' pskHex=$pskHex")
    }

    fun close() {
        stackClosed = true
        destroy(_thisPointer)
        Log.i(TAG, "Stack destroyed")
    }
}
