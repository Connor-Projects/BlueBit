package com.fitbit.goldengate.bindings

interface DataSinkDataSource : NativeReference {
    fun getAsDataSourcePointer(): Long
    fun getAsDataSinkPointer(): Long
}
