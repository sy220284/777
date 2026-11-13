package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Explicit user preferences enter generation; completed speech is never cut up afterwards. */
internal fun renderChatExpressionPreferences(phrases: List<String>, enabled: Boolean): String {
    if (!enabled) return ""
    val values = phrases.map(String::trim).filter(String::isNotBlank).distinct()
    if (values.isEmpty()) return ""
    return "【用户填写的表达偏好】\n避用表达：${JsonArray(values.map(::JsonPrimitive))}"
}
