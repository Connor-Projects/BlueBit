package com.fitbit.goldengate.bindings

class NativeReferenceCreationResult(
    val resultCode: Int,
    val pointer: Long
) {
    val isSuccess: Boolean
        get() = resultCode == 0 && pointer != 0L

    val result: GoldenGateNativeResult
        get() = GoldenGateNativeResult.getNativeResultFrom(resultCode)
}
