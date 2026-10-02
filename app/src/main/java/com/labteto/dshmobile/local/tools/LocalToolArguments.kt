package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.string(key: String): String =
    optionalString(key)?.takeIf { it.isNotBlank() } ?: error("缺少参数：$key")

internal fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.int(key: String, default: Int): Int {
    val value = this[key] ?: return default
    return value.jsonPrimitive.intOrNull
        ?: throw IllegalArgumentException("$key 必须是整数")
}

internal fun JsonObject.long(key: String, default: Long): Long {
    val value = this[key] ?: return default
    return value.jsonPrimitive.longOrNull
        ?: throw IllegalArgumentException("$key 必须是 64 位整数")
}

internal fun JsonObject.boolean(key: String, default: Boolean): Boolean {
    val value = this[key] ?: return default
    return value.jsonPrimitive.booleanOrNull
        ?: throw IllegalArgumentException("$key 必须是 true 或 false")
}
