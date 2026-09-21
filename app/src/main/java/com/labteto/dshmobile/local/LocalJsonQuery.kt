package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Minimal JSON path resolver for built-in json_query: a.b[0].c */
internal fun resolveJsonPath(root: JsonElement, query: String): JsonElement {
    val clean = query.trim()
    if (clean.isEmpty()) return root

    val token = Regex("""([^.\[\]]+)|\[(\d+)]""")
    var current = root
    var consumed = 0
    token.findAll(clean).forEach { match ->
        if (match.range.first != consumed) {
            val gap = clean.substring(consumed, match.range.first)
            require(gap == ".") { "json_query 路径格式无效：$query" }
        }
        val key = match.groups[1]?.value
        val index = match.groups[2]?.value?.toIntOrNull()
        current = when {
            key != null -> (current as? JsonObject)?.get(key)
                ?: error("JSON 字段不存在或当前节点不是对象：$key")
            index != null -> (current as? JsonArray)?.getOrNull(index)
                ?: error("JSON 数组下标越界或当前节点不是数组：$index")
            else -> current
        }
        consumed = match.range.last + 1
    }
    require(consumed == clean.length) { "json_query 路径格式无效：$query" }
    return current
}
