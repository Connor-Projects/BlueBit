package com.fitbit.goldengate.bindings.coap.data

class ContentFormatOption(val formatValue: Int) : Option(
    number = OptionNumber.CONTENT_FORMAT,
    value = IntOptionValue(formatValue)
)
