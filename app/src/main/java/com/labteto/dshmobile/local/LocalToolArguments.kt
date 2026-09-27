package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.string(key: String): String =
    optionalString(key)?.takeIf { it.isNotBlank() } ?: error("缺少参数：$key")

internal fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.int(key: String, default: Int): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default

internal fun JsonObject.long(key: String, default: Long): Long =
    optionalString(key)?.toLongOrNull() ?: default

internal fun JsonObject.boolean(key: String, default: Boolean): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default
