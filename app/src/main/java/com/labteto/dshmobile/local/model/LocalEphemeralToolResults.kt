package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_TOOL_RESULT_RETENTION_KEY = "_dsh_tool_result_retention"
private const val LOCAL_EPHEMERAL_VALUE = "ephemeral"
internal const val EPHEMERAL_TOOL_RESULT_PLACEHOLDER =
    "[短生命周期工具结果未持久化；如仍需要当前画面或瞬时数据，请重新调用对应工具。]"

internal fun localToolHistoryMessage(
    callId: String,
    content: String,
    retention: ToolResultRetention,
    images: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment> = emptyList(),
): JsonObject = buildJsonObject {
    put("role", "tool")
    put("tool_call_id", callId)
    put("content", content)
    if (images.isNotEmpty()) put("_dsh_tool_images", toolImageReferences(images))
    if (retention == ToolResultRetention.EPHEMERAL) {
        put(LOCAL_TOOL_RESULT_RETENTION_KEY, LOCAL_EPHEMERAL_VALUE)
    }
}

internal fun durableToolResultContent(
    content: String,
    retention: ToolResultRetention,
): String = if (retention == ToolResultRetention.EPHEMERAL) {
    EPHEMERAL_TOOL_RESULT_PLACEHOLDER
} else {
    content
}

internal fun durableModelHistorySnapshot(messages: List<JsonObject>): List<JsonObject> {
    var changed = false
    val projected = messages.map { message ->
        val ephemeral = message["role"]?.jsonPrimitive?.contentOrNull == "tool" &&
            message[LOCAL_TOOL_RESULT_RETENTION_KEY]?.jsonPrimitive?.contentOrNull == LOCAL_EPHEMERAL_VALUE
        if (!ephemeral) {
            message
        } else {
            changed = true
            buildJsonObject {
                message.forEach { (key, value) ->
                    if (key != "content" && key != LOCAL_TOOL_RESULT_RETENTION_KEY && key != "_dsh_tool_images") put(key, value)
                }
                put("content", EPHEMERAL_TOOL_RESULT_PLACEHOLDER)
            }
        }
    }
    return if (changed) projected else messages
}

internal fun toolImageReferences(images: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment>): kotlinx.serialization.json.JsonArray =
    buildLocalUserModelMessage("", images)["content"] as? kotlinx.serialization.json.JsonArray
        ?: kotlinx.serialization.json.JsonArray(emptyList())

/** All tool receipts stay adjacent; images follow the batch as legal multimodal user input. */
internal fun expandToolImageMessages(messages: List<JsonObject>, toolImageMode: Boolean = false): List<JsonObject> = buildList {
    val pending = mutableListOf<JsonObject>()
    fun flushImages() { addAll(pending); pending.clear() }
    messages.forEach { message ->
        if (message["role"]?.jsonPrimitive?.contentOrNull != "tool") flushImages()
        add(JsonObject(message - "_dsh_tool_images"))
        val images = message["_dsh_tool_images"] as? kotlinx.serialization.json.JsonArray
        if (!images.isNullOrEmpty()) pending += buildJsonObject {
            put("role", "user")
            put("content", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "工具结果图片，来源 call_id=" + message["tool_call_id"]?.jsonPrimitive?.contentOrNull)
                })
                if (toolImageMode) {
                    images.forEach { image ->
                        val reference = image as? JsonObject ?: error("工具图片引用格式无效")
                        val path = reference["path"]?.jsonPrimitive?.contentOrNull
                            ?: error("工具图片引用缺少工作区路径")
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "[工具图片工作区路径：$path。当前工具读图模式未发送像素；需要查看内容时使用可用的 vision_analyze_file 视觉工具，或切换原生图片模式。]")
                        })
                    }
                } else images.forEach { add(it) }
            })
        }
    }
    flushImages()
}
