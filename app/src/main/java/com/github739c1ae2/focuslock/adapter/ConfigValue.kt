package com.github739c1ae2.focuslock.adapter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ConfigValue {

    @Serializable
    @SerialName("boolean")
    data class BooleanValue(val value: Boolean) : ConfigValue()


    @Serializable
    @SerialName("string_list")
    data class StringListValue(val value: List<String>) : ConfigValue()

    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : ConfigValue()
}


fun Map<String, ConfigValue>.getBoolean(key: String, default: Boolean = false): Boolean {
    return (this[key] as? ConfigValue.BooleanValue)?.value ?: default
}

fun Map<String, ConfigValue>.getStringList(key: String): List<String> {
    return (this[key] as? ConfigValue.StringListValue)?.value ?: emptyList()
}

fun Map<String, ConfigValue>.getString(key: String, default: String = ""): String {
    return (this[key] as? ConfigValue.StringValue)?.value ?: default
}