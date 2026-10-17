package com.labteto.dshmobile.uicontract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Data-to-layout only. Does not interpret URLs, completion claims, actions or tool names. */
internal object LocalStructuredUiDataAdapter {
    fun nodes(encoded: String, budget: UiDocumentBudget): List<UiDocumentNode> {
        require(encoded.length <= budget.maxTextChars) { "text_budget" }
        if (encoded.trimStart().firstOrNull() !in listOf('{', '[')) {
            return listOf(UiDocumentNode.Text("result_text", encoded))
        }
        // Check nesting before JSON allocation, using the same document depth budget.
        var quoted = false
        var escaped = false
        var depth = 0
        encoded.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= budget.maxDepth) { "depth_budget" } }
                '}', ']' -> depth--
            }
        }
        val value = try { Json.parseToJsonElement(encoded) }
        catch (_: SerializationException) { return listOf(UiDocumentNode.Text("result_text", encoded)) }
        var count = 0
        fun id(): String {
            require(++count <= budget.maxNodes) { "node_budget" }
            return "result_$count"
        }
        fun display(value: JsonElement): String = if (value is JsonPrimitive && value.isString) value.content else value.toString()
        fun render(value: JsonElement, label: String, level: Int): UiDocumentNode {
            require(level <= budget.maxDepth) { "depth_budget" }
            val nodeId = id()
            return when (value) {
                is JsonObject -> UiDocumentNode.Disclosure(nodeId, label, value.map { (key, item) -> render(item, key, level + 1) })
                is JsonArray -> {
                    val objects = value.filterIsInstance<JsonObject>()
                    val headers = objects.firstOrNull()?.keys?.toList().orEmpty()
                    val table = objects.size == value.size && headers.isNotEmpty() && headers.size <= 8 &&
                        headers.all { it.isNotBlank() } && objects.all { row ->
                            row.keys == headers.toSet() && row.values.all { it is JsonPrimitive }
                        }
                    if (table) {
                        require(level + 1 <= budget.maxDepth) { "depth_budget" }
                        val tableId = id()
                        val cells = headers.size.toLong() * (objects.size.toLong() + 1)
                        require(count.toLong() + cells <= budget.maxNodes) { "table_budget" }
                        count += cells.toInt()
                        UiDocumentNode.Disclosure(nodeId, label, listOf(UiDocumentNode.Table(tableId,
                            headers, objects.map { row -> headers.map { display(row.getValue(it)) } })))
                    } else UiDocumentNode.Disclosure(nodeId, label, value.mapIndexed { index, item -> render(item, "${index + 1}", level + 1) })
                }
                else -> UiDocumentNode.Text(nodeId, "$label: ${display(value)}")
            }
        }
        return listOf(render(value, "工具返回数据（未核验）", 1))
    }
}
