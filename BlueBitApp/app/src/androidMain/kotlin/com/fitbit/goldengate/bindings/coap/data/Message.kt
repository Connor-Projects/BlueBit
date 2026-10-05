package com.fitbit.goldengate.bindings.coap.data

import java.util.LinkedList

interface Message {
    fun getOptions(): LinkedList<Option>
}
