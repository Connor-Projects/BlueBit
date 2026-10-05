package bluebit.core.crypto

import bluebit.core.bluetooth.BleServiceData
import bluebit.core.connection.VerificationResult
import javax.crypto.Cipher
import javax.crypto.SecretKey

class AdvertisementVerifier(
    private val keyProvider: KeyProvider
) {
    fun verify(serviceData: BleServiceData): VerificationResult {
        val key = keyProvider.getKey() ?: return VerificationResult.KeyUnavailable
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.doFinal(serviceData.secret)
            VerificationResult.Verified
        } catch (e: Exception) {
            VerificationResult.Error(e.message ?: "Verification failed")
        }
    }
}
