package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.tools.JsonSchemaValidator
import com.labteto.dshmobile.local.model.stableJsonSha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal data class LocalStructuredSubagentOutput(
    val value: JsonObject,
    val canonicalJson: String,
    val schemaDigest: String,
    val resultDigest: String,
)

internal data class LocalStructuredSubagentOutputFailure(
    val code: String,
    val detail: String,
)

internal fun localStructuredSubagentInstruction(schema: JsonObject): String =
    buildString {
        append("【结构化结果契约】\n")
        append("最终回答必须只输出一个 JSON 对象，不要使用 Markdown 代码块，不要添加解释、前后缀或额外文字。\n")
        append("该 JSON 必须严格符合下面的 schema；工具调用完成后再输出最终 JSON。\n")
        append("JSON Schema：")
        append(schema.toString())
    }

internal fun validateLocalStructuredSubagentOutput(
    raw: String,
    schema: JsonObject,
): Result<LocalStructuredSubagentOutput> {
    val text = raw.trim()
    if (text.isEmpty()) {
        return Result.failure(
            LocalStructuredSubagentOutputException(
                LocalStructuredSubagentOutputFailure(
                    code = "SUBAGENT_STRUCTURED_OUTPUT_EMPTY",
                    detail = "结构化子代理没有返回 JSON 对象",
                ),
            ),
        )
    }
    if (text.length > MAX_STRUCTURED_OUTPUT_CHARS) {
        return Result.failure(
            LocalStructuredSubagentOutputException(
                LocalStructuredSubagentOutputFailure(
                    code = "SUBAGENT_STRUCTURED_OUTPUT_TOO_LARGE",
                    detail = "结构化子代理结果超过大小上限",
                ),
            ),
        )
    }
    val value = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull()
        ?: return Result.failure(
            LocalStructuredSubagentOutputException(
                LocalStructuredSubagentOutputFailure(
                    code = "SUBAGENT_STRUCTURED_OUTPUT_INVALID_JSON",
                    detail = "最终结果必须是单个合法 JSON 对象，且不能包含 Markdown 代码块或额外文字",
                ),
            ),
        )

    JsonSchemaValidator.validate(value, schema)?.let { problem ->
        return Result.failure(
            LocalStructuredSubagentOutputException(
                LocalStructuredSubagentOutputFailure(
                    code = "SUBAGENT_STRUCTURED_OUTPUT_SCHEMA_MISMATCH",
                    detail = problem,
                ),
            ),
        )
    }

    val canonical = value.toString()
    return Result.success(
        LocalStructuredSubagentOutput(
            value = value,
            canonicalJson = canonical,
            schemaDigest = stableJsonSha256(schema),
            resultDigest = stableJsonSha256(value),
        ),
    )
}

internal class LocalStructuredSubagentOutputException(
    val failure: LocalStructuredSubagentOutputFailure,
) : IllegalArgumentException(failure.detail)

private const val MAX_STRUCTURED_OUTPUT_CHARS = 262_144
