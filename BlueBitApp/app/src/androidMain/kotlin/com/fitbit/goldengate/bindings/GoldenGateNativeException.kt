package com.fitbit.goldengate.bindings

class GoldenGateNativeException(
    message: String,
    val errorCode: Int,
    val domain: String,
    val details: String
) : Exception(message)
