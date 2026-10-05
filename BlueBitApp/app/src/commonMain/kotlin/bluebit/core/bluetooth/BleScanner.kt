package bluebit.core.bluetooth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface BleScanner {
    val isScanning: Boolean
    fun scanResults(): Flow<BleScanResult>
    fun scanningState(): Flow<Boolean>
    fun startScan()
    fun startUnfilteredScan()
    fun stopScan()
}

class NoOpBleScanner : BleScanner {
    override val isScanning: Boolean = false
    override fun scanResults(): Flow<BleScanResult> = emptyFlow()
    override fun scanningState(): Flow<Boolean> = emptyFlow()
    override fun startScan() {}
    override fun startUnfilteredScan() {}
    override fun stopScan() {}
}
