package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

enum class LocalModelProbe { TEXT, TOOL, IMAGE }

private const val TOOL_PROBE_NAME = "confirm_model_probe"
// Fixed red/blue sample. No local files, messages or customer data go into the probe.
private const val PROBE_IMAGE = "iVBORw0KGgoAAAANSUhEUgAAAGAAAAAwCAIAAABhdOiYAAAAcUlEQVR4nO3QQQnAABDEwPNvuhVQAdnCQP6Bueduqvr/qf4DAgQI0FL1HxAgQICmqv+AAAECtFT9BwQIEKCp6j8gQIAALVX/AQECBGiq+g8IECBAS9V/QIAAAZqq/gMCBAjQUvUfECBAgKaq/4B+DvQC/ozvAMlEKtoAAAAASUVORK5CYII="

internal fun LocalModelProbe.messages(): List<JsonObject> = listOf(buildJsonObject {
    put("role", "user")
    when (this@messages) {
        LocalModelProbe.TEXT -> put("content", "Reply with exactly: OK")
        LocalModelProbe.TOOL -> put("content", "Call confirm_model_probe once with no arguments.")
        LocalModelProbe.IMAGE -> put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "Which half of the image is red? Reply with LEFT or RIGHT only.")
            })
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/png;base64,$PROBE_IMAGE") }
            })
        })
    }
})

internal fun LocalModelProbe.tools(): JsonArray = buildJsonArray {
    if (this@tools == LocalModelProbe.TOOL) {
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", TOOL_PROBE_NAME)
                put("description", "Confirms that the model returned a structured function call. No action is executed.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {}
                    put("additionalProperties", false)
                }
            }
        })
    }
}

internal fun LocalModelProbe.verify(reply: LocalModelReply): String = when (this) {
    LocalModelProbe.TEXT -> "连接成功：普通对话已通过实际请求验证。工具调用、图片理解可单独检测。"
    LocalModelProbe.TOOL -> if (reply.toolCalls.singleOrNull()?.let {
        it.name == TOOL_PROBE_NAME && it.arguments.isEmpty()
    } == true) {
        "连接成功：已收到符合要求的工具调用。"
    } else "工具能力未通过：模型没有返回预期的结构化调用。"
    LocalModelProbe.IMAGE -> if (reply.content?.trim()?.equals("LEFT", ignoreCase = true) == true) {
        "连接成功：模型已识别测试图片。"
    } else "图片理解未通过：模型没有正确识别测试图片。"
}
