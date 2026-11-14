package com.labteto.dshmobile.harness.tools

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Code Mode uses a size-bounded declarative script, not arbitrary JVM/JS execution.
 * App execution dispatches each child through the normal coordinator; standalone harness
 * execution re-enters the registry. Both preserve the child's own checks, approvals and Hooks.
 */
class CodeModePlugin(private val liveTools: ToolRegistry) : HarnessPlugin {
    private data class CodeModeStepPlan(
        val name: String,
        val handler: HarnessTool,
        val args: JsonObject,
        val condition: JsonObject?,
        val continueOnReadError: Boolean,
    )

    override val id: String = "code-mode-orchestration"

    override suspend fun install(context: HarnessContext) {
        context.tools.register(HarnessTool(
            name = TOOL_NAME,
            schema = functionToolSchema(
                TOOL_NAME,
                "受控串行工具编排。支持已加载的核心工具与扩展工具、前序结果引用和 when 条件；扩展工具先通过 capability_search 选中，并在模型下一步装入真实工具表。每步保留原工具权限、审批、超时和审计；返回各步骤结果。",
                parameterSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("steps", buildJsonObject {
                            put("type", "array")
                            put("minItems", 1)
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("tool", buildJsonObject { put("type", "string") })
                                    put("args", buildJsonObject {
                                        put("type", "object")
                                        put("additionalProperties", true)
                                    })
                                    put("on_error", buildJsonObject {
                                        put("type", "string")
                                        put("description", "遇失败 stop（默认）或 continue；仅纯只读且无需审批的工具可继续，最终回执仍明确为部分失败")
                                        put("enum", buildJsonArray {
                                            add(JsonPrimitive("stop"))
                                            add(JsonPrimitive("continue"))
                                        })
                                    })
                                    put("when", buildJsonObject {
                                        put("type", "object")
                                        put("description", "前序持久结果匹配 equals 才执行，否则跳过")
                                        put("properties", buildJsonObject {
                                            put("step", buildJsonObject { put("type", "integer") })
                                            put("path", buildJsonObject { put("type", "string") })
                                            put("equals", buildJsonObject {})
                                        })
                                        put("required", buildJsonArray {
                                            add(JsonPrimitive("step"))
                                            add(JsonPrimitive("equals"))
                                        })
                                        put("additionalProperties", false)
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
            // The script is only a dispatcher: each real child tool independently checks
            // policy, mutation scope, hooks and user approval. Do not ask users twice.
            approvalPolicy = ToolApprovalPolicy.NEVER,
            exposure = ToolExposure.CORE,
            metadata = ToolMetadata(
                family = "workflow",
                discoveryKeywords = setOf("code mode", "编排", "批量工具"),
                usageNotes = listOf("核心工具即用；用 capability_search 加载的 MCP、GitHub、Android 等扩展工具可加入同一编排，须在本轮模型工具表中；保持逐步权限审批"),
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
        if (steps.isEmpty())
            return rejection("CODE_MODE_BAD_PLAN", "编排至少需要一个步骤")

        // Validate the complete script before running the first side-effecting step.
        val sampled = kotlinx.coroutines.currentCoroutineContext()[ToolBindingSnapshot]
        val validated = mutableListOf<CodeModeStepPlan>()
        for ((index, element) in steps.withIndex()) {
            val step = element as? JsonObject
                ?: return rejection("CODE_MODE_BAD_PLAN", "步骤 ${index + 1} 不是对象")
            val name = (step["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return rejection("CODE_MODE_BAD_PLAN", "步骤 ${index + 1} 缺少有效工具名称")
            if (name == TOOL_NAME)
                return rejection("CODE_MODE_RECURSION_DENIED", "Code Mode 不允许嵌套调用自身")
            val handler = liveTools.get(name)
                ?: return rejection("CODE_MODE_TOOL_MISSING", "步骤 ${index + 1} 的工具不可用：$name")
            if (handler.exposure == ToolExposure.INTERNAL)
                return rejection("CODE_MODE_UNAUTHORIZED_TOOL", "步骤 ${index + 1} 无法使用内部工具：$name")
            // Optional tools must be independently loaded by capability_search and frozen
            // in the exact tool table sent to the model. A script cannot discover or grant them.
            if (handler.exposure == ToolExposure.OPTIONAL && sampled == null)
                return rejection("CODE_MODE_UNSAMPLED_TOOL",
                    "步骤 ${index + 1} 的扩展工具尚未装入：$name；先使用 capability_search 精确选择，在下一模型步骤重试")
            if (sampled != null && sampled.codeModeChild(name) !== handler)
                return rejection("CODE_MODE_UNSAMPLED_TOOL",
                    "步骤 ${index + 1} 的工具未在本轮冻结工具表：$name；扩展工具需先通过 capability_search 选择")
            val arguments = step["args"] as? JsonObject
                ?: return rejection("CODE_MODE_BAD_PLAN", "步骤 ${index + 1} 缺少对象类型的参数")
            validateReferencePlan(arguments, index + 1)?.let { problem ->
                return rejection("CODE_MODE_BAD_REFERENCE", "步骤 ${index + 1} 的结果引用无效：$problem")
            }
            val condition = step["when"]
            if (condition != null) {
                val rule = condition as? JsonObject
                    ?: return rejection("CODE_MODE_BAD_CONDITION", "步骤 ${index + 1} 的 when 必须是对象")
                val source = (rule["step"] as? JsonPrimitive)?.content?.toIntOrNull()
                val path = rule["path"]
                if (rule.keys.any { it != "step" && it != "path" && it != "equals" } ||
                    source == null || source !in 1..index || rule["equals"] == null ||
                    (path != null && (path !is JsonPrimitive || !path.isString ||
                        parseReferencePath(path.content) == null)))
                    return rejection("CODE_MODE_BAD_CONDITION",
                        "步骤 ${index + 1} 的条件必须引用前序步骤及可验证字段")
            }
            val onError = (step["on_error"] as? JsonPrimitive)?.content ?: "stop"
            if (onError !in setOf("stop", "continue"))
                return rejection("CODE_MODE_BAD_PLAN", "步骤 ${index + 1} 的 on_error 只能是 stop 或 continue")
            if (onError == "continue" && (handler.access != ToolAccess.READ_ONLY ||
                    handler.approvalPolicy != ToolApprovalPolicy.NEVER))
                return rejection("CODE_MODE_UNSAFE_CONTINUE",
                    "步骤 ${index + 1} 只有无需审批的只读工具才能在失败后继续：$name")
            validated += CodeModeStepPlan(name, handler, arguments, condition as? JsonObject,
                onError == "continue")
        }

        val observations = mutableListOf<JsonObject>()
        // Preserve original ordinals even when a conditional step is skipped.
        val completedResults = mutableListOf<ToolResult?>()
        var partialFailure = false
        for ((index, entry) in validated.withIndex()) {
            val (name, handler, arguments, condition, continueOnReadError) = entry
            if (condition != null) {
                val sourceStep = (condition.getValue("step") as JsonPrimitive).content.toInt()
                val reference = buildJsonObject {
                    put(RESULT_REFERENCE_KEY, sourceStep)
                    condition["path"]?.let { put("path", it) }
                }
                val observed = try {
                    resolveReferences(reference, completedResults)
                } catch (error: StepReferenceException) {
                    observations += buildJsonObject {
                        put("index", index + 1)
                        put("tool", name)
                        put("status", "reference_failed")
                        put("hint", error.message ?: "条件来源没有持久结果")
                    }
                    return receipt(observations, "stopped", "CODE_MODE_REFERENCE_FAILED")
                }
                if (observed != condition.getValue("equals")) {
                    observations += buildJsonObject {
                        put("index", index + 1)
                        put("tool", name)
                        put("status", "skipped")
                        put("condition_step", sourceStep)
                        put("execution_started", false)
                    }
                    completedResults += null
                    continue
                }
            }
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
                // The app coordinator owns approvals, workspace leases and durable execution IDs.
                // Reusing the parent callback would check the child against code_mode_run and
                // incorrectly reject even the first read/bash step, or attribute it to the parent.
                // Standalone harness clients keep the original registry execution path.
                val nested = original.dispatchNestedTool
                if (nested != null) {
                    val runChild: suspend () -> Pair<ToolResult, Boolean> = {
                        nested(name, resolvedArguments, resolvedArguments.toString(), index + 1)
                    }
                    val (result, started) = if (sampled == null) runChild()
                        else withContext(CodeModeStepScope(sampled, handler)) { runChild() }
                    ToolInvocationResult(result, started)
                } else {
                    // ToolRegistry owns each tool's deadline; no second script watchdog.
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
            val result = outcome.result
            observations += buildJsonObject {
                put("index", index + 1)
                put("tool", name)
                put("status", if (result.isError && continueOnReadError)
                    "error_continued" else if (result.isError) "error" else "completed")
                put("execution_started", outcome.executionStarted)
                result.errorCode?.let { put("error_code", it) }
                // Nested tool calls must not lose Hook diagnostics when the wrapper succeeds.
                if (outcome.postHookFailures.isNotEmpty()) {
                    put("post_hook_failures", buildJsonArray {
                        outcome.postHookFailures.forEach { failure ->
                            add(buildJsonObject {
                                put("hook_id", failure.hookId)
                                put("failure_type", failure.failureType)
                            })
                        }
                    })
                }
                if (result.retention == ToolResultRetention.DURABLE) {
                    put("content", result.content.take(MAX_RESULT_CHARS))
                    if (result.content.length > MAX_RESULT_CHARS) put("content_truncated", true)
                } else {
                    put("content", "临时结果已处理，原始内容不进入持久脚本回执")
                }
            }
            if (result.isError) {
                if (!continueOnReadError)
                    return receipt(observations, "stopped", "CODE_MODE_STEP_FAILED")
                partialFailure = true
                completedResults += null
                continue
            }
            completedResults += result
        }
        return if (partialFailure) receipt(observations, "partial", "CODE_MODE_PARTIAL_FAILED")
            else receipt(observations, "completed", null)
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
                    parseReferencePath(path.content) == null) ->
                    "字段路径无效"
                else -> null
            }
        } else node.values.firstNotNullOfOrNull { validateReferencePlan(it, stepNumber) }
        is JsonArray -> node.firstNotNullOfOrNull { validateReferencePlan(it, stepNumber) }
        else -> null
    }

    private class StepReferenceException(message: String) : IllegalArgumentException(message)

    /**
     * One path grammar for result arguments and conditional steps. Accept ordinary
     * dotted keys and familiar JSONPath selectors, including array brackets and
     * quoted keys containing dots. Unsupported selectors fail before any tool runs.
     */
    private fun parseReferencePath(raw: String): List<String>? {
        if (raw.isEmpty() || raw == "$") return emptyList()
        var cursor = 0
        if (raw[0] == '
    /** Only durable results can be reused. Resolved inputs pass through ToolRegistry again,
     * retaining schema validation, approval, registration identity and Hooks. */
    private fun resolveReferences(node: JsonElement, history: List<ToolResult?>): JsonElement = when (node) {
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
            for (segment in (parseReferencePath(path)
                ?: throw StepReferenceException("步骤 $ordinal 的字段路径无效：$path"))) {
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
                put("skipped_steps", steps.count { it["status"]?.jsonPrimitive?.content == "skipped" })
                put("failed_steps", steps.count {
                    it["status"]?.jsonPrimitive?.content in setOf("error", "error_continued",
                        "reference_failed", "unknown_after_exception")
                })
                put("steps", JsonArray(steps))
            }.toString(),
            isError = failureCode != null,
            errorCode = failureCode,
            retryable = false,
            recoveryHint = failureCode?.let {
                if (status == "partial") "部分只读步骤失败，其余成功结果已保留；可单独补查失败步骤。"
                else "停止在失败步骤。先核对每步执行回执和文件状态，避免重复具有副作用的操作。"
            },
        )

    private companion object {
        const val TOOL_NAME = "code_mode_run"
        const val MAX_SCRIPT_CHARS = 256_000
        const val MAX_REFERENCE_SOURCE_CHARS = 128_000
        const val MAX_REFERENCE_VALUE_CHARS = 8_192
        const val RESULT_REFERENCE_KEY = "\$step_result"
        const val MAX_RESULT_CHARS = 4_000
    }
}
) {
            cursor = 1
            if (cursor < raw.length && raw[cursor] == '.') cursor++
            else if (cursor < raw.length && raw[cursor] != '[') return null
        }
        if (cursor >= raw.length) return null
        val parts = mutableListOf<String>()
        var hasSegment = false
        while (cursor < raw.length) {
            when (raw[cursor]) {
                '.' -> {
                    if (!hasSegment || cursor == raw.lastIndex) return null
                    cursor++
                    hasSegment = false
                }
                '[' -> {
                    val close = raw.indexOf(']', cursor + 1)
                    if (close < 0) return null
                    val token = raw.substring(cursor + 1, close)
                    val segment = when {
                        token.matches(Regex("""0|[1-9][0-9]*""")) -> token
                        token.length >= 2 && token.first() == '"' && token.last() == '"' ->
                            try {
                                (Json.parseToJsonElement(token) as? JsonPrimitive)
                                    ?.takeIf { it.isString }?.content
                            } catch (_: kotlinx.serialization.SerializationException) { null }
                        token.length >= 2 && token.first() == '\'' && token.last() == '\'' &&
                            !token.substring(1, token.lastIndex).contains('\'') ->
                            token.substring(1, token.lastIndex)
                        else -> null
                    }
                    if (segment.isNullOrEmpty()) return null
                    parts += segment
                    cursor = close + 1
                    hasSegment = true
                }
                ']' -> return null
                else -> {
                    if (hasSegment) return null
                    val start = cursor
                    while (cursor < raw.length && raw[cursor] !in charArrayOf('.', '[', ']'))
                        cursor++
                    val key = raw.substring(start, cursor)
                    if (key.isBlank()) return null
                    parts += key
                    hasSegment = true
                }
            }
        }
        return parts.takeIf { hasSegment }
    }

    /** Only durable results can be reused. Resolved inputs pass through ToolRegistry again,
     * retaining schema validation, approval, registration identity and Hooks. */
    private fun resolveReferences(node: JsonElement, history: List<ToolResult?>): JsonElement = when (node) {
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
                put("skipped_steps", steps.count { it["status"]?.jsonPrimitive?.content == "skipped" })
                put("failed_steps", steps.count {
                    it["status"]?.jsonPrimitive?.content in setOf("error", "error_continued",
                        "reference_failed", "unknown_after_exception")
                })
                put("steps", JsonArray(steps))
            }.toString(),
            isError = failureCode != null,
            errorCode = failureCode,
            retryable = false,
            recoveryHint = failureCode?.let {
                if (status == "partial") "部分只读步骤失败，其余成功结果已保留；可单独补查失败步骤。"
                else "停止在失败步骤。先核对每步执行回执和文件状态，避免重复具有副作用的操作。"
            },
        )

    private companion object {
        const val TOOL_NAME = "code_mode_run"
        const val MAX_SCRIPT_CHARS = 256_000
        const val MAX_REFERENCE_SOURCE_CHARS = 128_000
        const val MAX_REFERENCE_VALUE_CHARS = 8_192
        const val RESULT_REFERENCE_KEY = "\$step_result"
        val REFERENCE_PATH = Regex("""[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*""")
        const val MAX_RESULT_CHARS = 4_000
    }
}
