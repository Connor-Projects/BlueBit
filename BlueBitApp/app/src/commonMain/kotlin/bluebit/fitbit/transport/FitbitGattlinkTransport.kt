package bluebit.fitbit.transport

import kotlinx.coroutines.flow.Flow

interface FitbitGattlinkTransport {
    suspend fun write(data: ByteArray): Result<Unit>
    val incomingFrames: Flow<ByteArray>
}
