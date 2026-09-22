package com.labteto.dshmobile.local

import kotlinx.serialization.json.*

internal fun parseLanguageServerCommand(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val values = Json.parseToJsonElement(text).jsonArray
    require(values.size in 1..64)
    return values.map {
        val value = it.jsonPrimitive
        require(value.isString && value.content.isNotBlank() && '\u0000' !in value.content)
        value.content
    }
}
