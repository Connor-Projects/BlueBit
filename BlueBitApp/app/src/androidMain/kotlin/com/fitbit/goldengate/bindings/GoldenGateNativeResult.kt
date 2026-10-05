package com.fitbit.goldengate.bindings

open class GoldenGateNativeResult(val code: Int, val title: String) {

    companion object {
        val GG_SUCCESS = GG_SUCCESS()
        val GG_FAILURE = GG_FAILURE()
        val GG_ERROR_WOULD_BLOCK = GG_ERROR_WOULD_BLOCK()

        fun getNativeResultFrom(code: Int): GoldenGateNativeResult {
            return when (code) {
                0 -> GG_SUCCESS
                -2 -> GG_ERROR_WOULD_BLOCK
                else -> GG_FAILURE
            }
        }
    }

    class GG_SUCCESS : GoldenGateNativeResult(0, "Success")
    class GG_FAILURE : GoldenGateNativeResult(-1, "Failure")
    class GG_ERROR_WOULD_BLOCK : GoldenGateNativeResult(-2, "Would block")
}
