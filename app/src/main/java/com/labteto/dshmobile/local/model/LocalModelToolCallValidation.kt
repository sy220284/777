package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelFailureOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** No transport or history adapter may invent executable arguments or call identities. */
internal fun validatedModelToolCall(
    id: JsonElement?,
    name: JsonElement?,
    arguments: JsonElement?,
    code: String,
    retryable: Boolean = false,
): LocalToolCall {
    fun invalid(detail: String, cause: Throwable? = null): Nothing {
        val providerReply = code in setOf("MODEL_STREAM_PROTOCOL", "MODEL_RESPONSE_PROTOCOL")
        throw LocalModelException(
            code = code, message = "工具调用无效：$detail",
            retryable = if (providerReply) false else retryable,
            // JSON decoding failures can include the entire sensitive tool input in cause.message.
            cause = cause?.let { IllegalArgumentException("tool arguments decode: " + it::class.java.simpleName) },
            providerCode = if (providerReply) "invalid_tool_arguments" else null,
            admissionState = if (providerReply) LocalModelAdmissionState.ADMITTED
                else LocalModelAdmissionState.NOT_APPLICABLE,
            continuationEligible = providerReply,
            origin = if (providerReply) LocalModelFailureOrigin.STREAM_PARSE else null,
        )
    }
    fun string(value: JsonElement?, field: String): String {
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString || primitive.content.isBlank()) {
            invalid("$field 必须是非空字符串")
        }
        return primitive.content
    }
    val callId = string(id, "id")
    val toolName = string(name, "name")
    val raw = string(arguments, "arguments")
    val parsed = try {
        Json.parseToJsonElement(raw) as? JsonObject ?: invalid("arguments 必须是 JSON 对象")
    } catch (error: LocalModelException) {
        throw error
    } catch (error: Exception) {
        invalid("arguments 不是完整的 JSON 对象", error)
    }
    return LocalToolCall(callId, toolName, parsed, raw)
}

internal fun requireUniqueModelToolCallIds(
    calls: List<LocalToolCall>,
    code: String,
    retryable: Boolean = false,
) {
    val seen = hashSetOf<String>()
    if (calls.any { !seen.add(it.id) }) {
        val providerReply = code in setOf("MODEL_STREAM_PROTOCOL", "MODEL_RESPONSE_PROTOCOL")
        throw LocalModelException(
            code = code, message = "工具调用包含重复 id",
            retryable = if (providerReply) false else retryable,
            providerCode = if (providerReply) "duplicate_tool_call_id" else null,
            admissionState = if (providerReply) LocalModelAdmissionState.ADMITTED
                else LocalModelAdmissionState.NOT_APPLICABLE,
            continuationEligible = providerReply,
            origin = if (providerReply) LocalModelFailureOrigin.STREAM_PARSE else null,
        )
    }
}
