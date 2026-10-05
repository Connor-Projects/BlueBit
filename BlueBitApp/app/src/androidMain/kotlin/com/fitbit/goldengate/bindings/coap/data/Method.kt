package com.fitbit.goldengate.bindings.coap.data

enum class Method(val value: Byte) {
    GET(1),
    POST(2),
    PUT(3),
    DELETE(4);

    companion object {
        @JvmStatic
        fun fromValue(b: Byte): Method {
            return values().firstOrNull { it.value == b }
                ?: throw NoSuchElementException("No Method for value $b")
        }
    }
}
