package com.fitbit.goldengate.bindings.coap.data

enum class OptionNumber(val value: Short) {
    IF_MATCH(1),
    ETAG(4),
    IF_NONE_MATCH(5),
    LOCATION_PATH(8),
    URI_PATH(11),
    CONTENT_FORMAT(12),
    MAX_AGE(14),
    URI_QUERY(15),
    ACCEPT(17),
    LOCATION_QUERY(20),
    BLOCK2(23),
    BLOCK1(27),
    START_OFFSET(2048);

    companion object {
        @JvmStatic
        fun fromValue(s: Short): OptionNumber {
            return values().firstOrNull { it.value == s }
                ?: throw NoSuchElementException("No OptionNumber for value $s")
        }
    }
}
