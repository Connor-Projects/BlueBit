package com.fitbit.goldengate.bindings.coap.data

import java.util.LinkedList
import java.util.NoSuchElementException

class OptionsBuilder {
    private val options = LinkedList<Option>()

    fun build(): LinkedList<Option> = options

    private fun optionInternal(s: Short, obj: Any?) {
        try {
            val option = when (OptionNumber.fromValue(s)) {
                OptionNumber.IF_MATCH -> {
                    require(obj is ByteArray)
                    IfMatchOption(obj)
                }
                OptionNumber.ETAG -> {
                    require(obj is ByteArray)
                    EtagOption(obj)
                }
                OptionNumber.IF_NONE_MATCH -> IfNoneMatchOption
                OptionNumber.LOCATION_PATH -> {
                    require(obj is String)
                    LocationPathOption(obj)
                }
                OptionNumber.URI_PATH -> {
                    require(obj is String)
                    UriPathOption(obj)
                }
                OptionNumber.CONTENT_FORMAT -> {
                    require(obj is Int)
                    ContentFormatOption(obj)
                }
                OptionNumber.MAX_AGE -> {
                    require(obj is Int)
                    MaxAgeOption(obj)
                }
                OptionNumber.URI_QUERY -> {
                    require(obj is String)
                    UriQueryOption(obj)
                }
                OptionNumber.ACCEPT -> {
                    require(obj is Int)
                    AcceptOption(obj)
                }
                OptionNumber.LOCATION_QUERY -> {
                    require(obj is String)
                    LocationQueryOption(obj)
                }
                OptionNumber.BLOCK2 -> {
                    require(obj is Int)
                    Block2Option(obj)
                }
                OptionNumber.BLOCK1 -> {
                    require(obj is Int)
                    Block1Option(obj)
                }
                OptionNumber.START_OFFSET -> {
                    require(obj is Int)
                    StartOffset(obj)
                }
            }
            options.add(option)
        } catch (_: NoSuchElementException) {
            // Unknown option number — ignore
        }
    }

    fun option(s: Short) {
        optionInternal(s, null)
    }

    fun option(s: Short, i: Int) {
        optionInternal(s, i)
    }

    fun option(s: Short, str: String) {
        optionInternal(s, str)
    }

    fun option(s: Short, bArr: ByteArray) {
        optionInternal(s, bArr)
    }
}
