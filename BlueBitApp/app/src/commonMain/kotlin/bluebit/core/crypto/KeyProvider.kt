package bluebit.core.crypto

import javax.crypto.SecretKey

fun interface KeyProvider {
    fun getKey(): SecretKey?
}
