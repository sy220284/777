package com.labteto.dshmobile.harness.tools

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The first Code Mode language is a bounded declarative script, not arbitrary JVM/JS execution.
 * Every step re-enters the original registry, so a script cannot bypass tools, approvals or Hooks.
 */
class CodeModePlugin(private val liveTools: ToolRegistry) : HarnessPlugin {
    override val id: String = "code-mode-orchestration"

    override suspend fun install(context: HarnessContext) {
        context.tools.register(HarnessTool(
            name = TOOL_NAME,
            schema = functionToolSchema(
                TOOL_NAME,
                "受控串行工具编排。按顺序执行最多8步；每步单独经过工具权限、审批及审计。遇到失败即停止。不能执行任意脚本、访问内部工具或自动重试未知副作用。",
                parameterSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("steps", buildJsonObject {
                            put("type", "array")
                            put("minItems", 1)
                            put("maxItems", MAX_STEPS)
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("tool", buildJsonObject { put("type", "string") })
                                    put("args", buildJsonObject {
                                        put("type", "object")
                                        put("additionalProperties", true)
                                    })
                                })
                                put("required", buildJsonArray {
                                    add(JsonPrimitive("tool"))
                                    add(JsonPrimitive("args"))
                                })
                                put("additionalProperties", false)
                            })
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("steps")) })
                    put("additionalProperties", false)
                },
            ),
            access = ToolAccess.AGENT_CONTROL,
            approvalPolicy = ToolApprovalPolicy.ALWAYS,
            exposure = ToolExposure.CORE,
            metadata = ToolMetadata(
                family = "workflow",
                discoveryKeywords = setOf("code mode", "编排", "批量工具"),
                usageNotes = listOf("CORE 工具按顺序逐步审批；可引用前序持久回执字段，失败停止且不自动重试"),
            ),
            executor = HarnessToolExecutor { originalContext, input, _ ->
                executePlan(originalContext, input)
            },
        ))
    }

    override suspend fun uninstall(context: HarnessContext) {
        context.tools.unregister(TOOL_NAME)
    }

    private suspend fun executePlan(original: ToolContext, script: JsonObject): ToolResult {
        if (kotlinx.coroutines.currentCoroutineContext()[CodeModeStepScope] != null)
            return rejection("CODE_MODE_RECURSION_DENIED", "编排子工具不能再次发起 Code Mode")
        if (script.toString().length > MAX_SCRIPT_CHARS)
            return rejection("CODE_MODE_BAD_PLAN", "编排脚本超过长度限制")
        val steps = (script["steps"] as? JsonArray)?.toList()
            ?: return rejection("CODE_MODE_BAD_PLAN", "缺少步骤数组")
        if (steps.size !in 1..MAX_STEPS)
            return rejection("CODE_MODE_BAD_PLAN", "编排步骤数量需为 1—$MAX_STEPS")

        // Validate the complete script before running the first side-effecting step.
        val sampled = kotlinx.coroutines.currentCoroutineContext()[ToolBindingSnapshot]
        val validated = mutableListOf<Triple<String, HarnessTool, JsonObject>>()
        for ((index, element) in steps.withIndex()) {
            val step = element as? JsonObject
                ?: return rejection("CODE_MODE_BAD_PLAN", "步骤 ${index + 1} 不是对象")
            val name = (step["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return rejection("CODE_MODE_BAD_PLAN", "步骤 ${index + 1} 缺少有效工具名称")
            if (name == TOOL_NAME)
                return rejection("CODE_MODE_RECURSION_DENIED", "Code Mode 不允许嵌套调用自身")
            val handler = liveTools.get(name)
                ?: return rejection("CODE_MODE_TOOL_MISSING", "步骤 ${index + 1} 的工具不可用：$name")
            if (handler.exposure != ToolExposure.CORE)
                return rejection("CODE_MODE_UNAUTHORIZED_TOOL", "步骤 ${index + 1} 未获工具发现授权：$name")
            if (sampled != null && sampled.codeModeChild(name) !== handler)
                return rejection("CODE_MODE_UNSAMPLED_TOOL", "步骤 ${index + 1} 的工具未在本轮采样时锁定：$name")
            val arguments = step["args"] as? JsonObject
                ?: return rejection("CODE_MODE_BAD_PLAN", "步骤 ${index + 1} 缺少对象类型的参数")
            validateReferencePlan(arguments, index + 1)?.let { problem ->
                return rejection("CODE_MODE_BAD_REFERENCE", "步骤 ${index + 1} 的结果引用无效：$problem")
            }
            validated += Triple(name, handler, arguments)
        }

        val observations = mutableListOf<JsonObject>()
        val completedResults = mutableListOf<ToolResult>()
        for ((index, entry) in validated.withIndex()) {
            val (name, handler, arguments) = entry
            val resolvedArguments = try {
                resolveReferences(arguments, completedResults) as JsonObject
            } catch (error: StepReferenceException) {
                observations += buildJsonObject {
                    put("index", index + 1)
                    put("tool", name)
                    put("status", "reference_failed")
                    put("hint", error.message ?: "前序步骤没有可用的持久结果")
                }
                return receipt(observations, "stopped", "CODE_MODE_REFERENCE_FAILED")
            }
            // The parent call receipt and each child tool receipt have different call identities.
            val attrs = original.attributes + (
                "call_id" to (original.attributes["call_id"]?.toString().orEmpty()
                    .ifBlank { "code-mode" } + "/step-${index + 1}")
            )
            val child = original.copy(attributes = attrs, expectedRegistration = handler)
            val outcome = try {
                withTimeoutOrNull(STEP_TIMEOUT_MILLIS) {
                    if (sampled == null) {
                        liveTools.executeTracked(name, resolvedArguments, resolvedArguments.toString(), child)
                    } else withContext(CodeModeStepScope(sampled, handler)) {
                        liveTools.executeTracked(name, resolvedArguments, resolvedArguments.toString(), child)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                observations += buildJsonObject {
                    put("index", index + 1)
                    put("tool", name)
                    put("status", "unknown_after_exception")
                    put("error_type", failure.javaClass.simpleName)
                    put("hint", "工具出现执行异常，副作用状态未知；核查审计回执后再决定重试")
                }
                return receipt(observations, "stopped", "CODE_MODE_STEP_EXCEPTION")
            }
            if (outcome == null) {
                observations += buildJsonObject {
                    put("index", index + 1)
                    put("tool", name)
                    put("status", "unknown_after_timeout")
                    put("hint", "调用已超时，副作用可能已发生；先核对审计回执再决定是否重试")
                }
                return receipt(observations, "stopped", "CODE_MODE_STEP_TIMEOUT")
            }
            val result = outcome.result
            observations += buildJsonObject {
                put("index", index + 1)
                put("tool", name)
                put("status", if (result.isError) "error" else "completed")
                put("execution_started", outcome.executionStarted)
                result.errorCode?.let { put("error_code", it) }
                if (result.retention == ToolResultRetention.DURABLE) {
                    put("content", result.content.take(MAX_RESULT_CHARS))
                    if (result.content.length > MAX_RESULT_CHARS) put("content_truncated", true)
                } else {
                    put("content", "临时结果已处理，原始内容不进入持久脚本回执")
                }
            }
            if (result.isError) return receipt(observations, "stopped", "CODE_MODE_STEP_FAILED")
            completedResults += result
        }
        return receipt(observations, "completed", null)
    }


    /** All references are checked before the first side-effecting tool is entered. */
    private fun validateReferencePlan(node: JsonElement, stepNumber: Int): String? = when (node) {
        is JsonObject -> if (RESULT_REFERENCE_KEY in node) {
            val index = (node[RESULT_REFERENCE_KEY] as? JsonPrimitive)?.content?.toIntOrNull()
            val path = node["path"]
            when {
                node.keys.any { it != RESULT_REFERENCE_KEY && it != "path" } -> "包含未知字段"
                index == null || index !in 1 until stepNumber -> "只能引用更早的步骤"
                path != null && (path !is JsonPrimitive || !path.isString ||
                    (path.content.isNotEmpty() && !REFERENCE_PATH.matches(path.content))) ->
                    "字段路径无效"
                else -> null
            }
        } else node.values.firstNotNullOfOrNull { validateReferencePlan(it, stepNumber) }
        is JsonArray -> node.firstNotNullOfOrNull { validateReferencePlan(it, stepNumber) }
        else -> null
    }

    private class StepReferenceException(message: String) : IllegalArgumentException(message)

    /** Only durable results can be reused. Resolved inputs pass through ToolRegistry again,
     * retaining schema validation, approval, registration identity and Hooks. */
    private fun resolveReferences(node: JsonElement, history: List<ToolResult>): JsonElement = when (node) {
        is JsonObject -> if (RESULT_REFERENCE_KEY in node) {
            val ordinal = (node[RESULT_REFERENCE_KEY] as JsonPrimitive).content.toInt()
            val source = history.getOrNull(ordinal - 1)
                ?: throw StepReferenceException("步骤 $ordinal 尚无执行回执")
            if (source.retention != ToolResultRetention.DURABLE)
                throw StepReferenceException("步骤 $ordinal 的临时结果不可传递")
            if (source.content.length > MAX_REFERENCE_SOURCE_CHARS)
                throw StepReferenceException("步骤 $ordinal 的结果过大")
            val parsed = try {
                Json.parseToJsonElement(source.content)
            } catch (_: kotlinx.serialization.SerializationException) {
                JsonPrimitive(source.content)
            }
            var selected: JsonElement = parsed
            val path = (node["path"] as? JsonPrimitive)?.content.orEmpty()
            if (path.isNotEmpty()) for (segment in path.split('.')) {
                selected = when (val value = selected) {
                    is JsonObject -> value[segment]
                    is JsonArray -> segment.toIntOrNull()?.let { index -> value.getOrNull(index) }
                    else -> null
                } ?: throw StepReferenceException("步骤 $ordinal 的字段不存在：$path")
            }
            if (selected.toString().length > MAX_REFERENCE_VALUE_CHARS)
                throw StepReferenceException("步骤 $ordinal 的字段超过传递长度限制")
            selected
        } else JsonObject(node.mapValues { (_, element) -> resolveReferences(element, history) })
        is JsonArray -> JsonArray(node.map { resolveReferences(it, history) })
        else -> node
    }

    private fun rejection(code: String, detail: String): ToolResult = ToolResult(
        content = detail, isError = true, errorCode = code,
        retryable = false, recoveryHint = "请修正脚本和工具授权后重新检查步骤。",
    )

    private fun receipt(steps: List<JsonObject>, status: String, failureCode: String?): ToolResult =
        ToolResult(
            content = buildJsonObject {
                put("status", status)
                put("completed_steps", steps.count { it["status"]?.jsonPrimitive?.content == "completed" })
                put("steps", JsonArray(steps))
            }.toString(),
            isError = failureCode != null,
            errorCode = failureCode,
            retryable = false,
            recoveryHint = failureCode?.let {
                "停止在失败步骤。先核对每步执行回执和文件状态，避免重复具有副作用的操作。"
            },
        )

    private companion object {
        const val TOOL_NAME = "code_mode_run"
        const val MAX_STEPS = 8
        const val MAX_SCRIPT_CHARS = 32_000
        const val MAX_REFERENCE_SOURCE_CHARS = 128_000
        const val MAX_REFERENCE_VALUE_CHARS = 8_192
        const val RESULT_REFERENCE_KEY = "\$step_result"
        val REFERENCE_PATH = Regex("""[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*""")
        const val MAX_RESULT_CHARS = 4_000
        const val STEP_TIMEOUT_MILLIS = 90_000L
    }
}
