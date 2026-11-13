package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.interop.mcp.McpElicitationReply
import com.labteto.dshmobile.interop.mcp.McpElicitationRequest
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.interaction.LocalQuestionFormField
import com.labteto.dshmobile.local.interaction.LOCAL_QUESTION_CANCELLED_RESPONSE
import com.labteto.dshmobile.local.interaction.LOCAL_MCP_QUESTION_DECLINE
import com.labteto.dshmobile.local.interaction.LOCAL_MCP_QUESTION_URL_ACCEPT
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bind one remote prompt to the originating Work run; do not create a second UI owner. */
internal object LocalMcpElicitationUiBridge {
    suspend fun answer(
        runs: LocalWorkRunRegistry,
        sessionId: String?,
        serverId: String,
        callId: String,
        request: McpElicitationRequest,
    ): McpElicitationReply {
        val run = sessionId?.let(runs::live) ?: return McpElicitationReply("cancel")
        if (run.runHandle.cancellationRequested) return McpElicitationReply("cancel")
        val fields = request.requestedSchema?.let(::fromSchema)
        val question = LocalQuestion(
            callId = callId,
            question = "MCP 服务「$serverId」请求补充信息：\n" + request.message,
            mcpFormFields = fields,
            mcpUrl = request.url,
        )
        val response = run.interactions.awaitQuestion(question)
        if (run.runHandle.cancellationRequested || runs.live(run.sessionId) !== run) {
            return McpElicitationReply("cancel")
        }
        return when (response) {
            LOCAL_QUESTION_CANCELLED_RESPONSE -> McpElicitationReply("cancel")
            LOCAL_MCP_QUESTION_DECLINE -> McpElicitationReply("decline")
            LOCAL_MCP_QUESTION_URL_ACCEPT -> {
                require(request.mode == "url") { "MCP 表单不能使用外部网页的完成回执" }
                McpElicitationReply("accept")
            }
            else -> {
                require(request.mode == "form") { "MCP 外部追问必须先征得网页操作同意" }
                McpElicitationReply("accept",
                    Json.parseToJsonElement(response) as? JsonObject
                        ?: error("MCP 表单答复必须为对象"))
            }
        }
    }

    internal fun fromSchema(schema: JsonObject): List<LocalQuestionFormField> {
        val properties = requireNotNull(schema["properties"] as? JsonObject)
        val required = (schema["required"] as? JsonArray)
            ?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        return properties.map { (name, raw) ->
            val spec = raw.jsonObject
            LocalQuestionFormField(
                name = name,
                title = spec["title"]?.jsonPrimitive?.contentOrNull
                    ?: spec["description"]?.jsonPrimitive?.contentOrNull ?: name,
                kind = spec["type"]?.jsonPrimitive?.content ?: "string",
                required = name in required,
                options = (spec["enum"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                defaultValue = spec["default"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                minLength = spec["minLength"]?.jsonPrimitive?.content?.toIntOrNull(),
                maxLength = spec["maxLength"]?.jsonPrimitive?.content?.toIntOrNull(),
                minimum = spec["minimum"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                maximum = spec["maximum"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            )
        }
    }
}
