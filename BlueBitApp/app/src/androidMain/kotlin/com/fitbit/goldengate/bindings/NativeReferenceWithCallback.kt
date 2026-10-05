package com.fitbit.goldengate.bindings

interface NativeReferenceWithCallback : NativeReference {
    fun getThisPointerWrapper(): Long
    fun onFree()
    fun setThisPointerWrapper(j: Long)
}
