package com.fitbit.goldengate.bindings.coap.data

class UriPathOption(val stringValue: String) : Option(
    number = OptionNumber.URI_PATH,
    value = StringOptionValue(stringValue)
)
