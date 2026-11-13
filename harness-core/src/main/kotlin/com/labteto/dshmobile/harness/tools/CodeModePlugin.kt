package com.labteto.dshmobile.harness.tools

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
                usageNotes = listOf("只执行已经在本轮授权的 CORE 工具；逐步审批，失败即停止，不重试"),
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
        require(script.toString().length <= MAX_SCRIPT_CHARS) { "编排脚本超过长度限制" }
        val steps = (script["steps"] as? JsonArray)?.toList()
            ?: throw IllegalArgumentException("缺少步骤数组")
        require(steps.size in 1..MAX_STEPS) { "编排步骤数量需为 1—$MAX_STEPS" }

        val validated = steps.mapIndexed { index, element ->
            val step = element as? JsonObject
                ?: throw IllegalArgumentException("步骤 ${index + 1} 不是对象")
            val name = (step["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("步骤 ${index + 1} 缺少有效工具名称")
            require(name != TOOL_NAME) { "Code Mode 不允许嵌套调用自身" }
            val handler = liveTools.get(name)
                ?: throw IllegalArgumentException("步骤 ${index + 1} 的工具不可用：$name")
            require(handler.exposure == ToolExposure.CORE) {
                "步骤 ${index + 1} 无权使用未公开或未发现的工具：$name"
            }
            val arguments = step["args"] as? JsonObject
                ?: throw IllegalArgumentException("步骤 ${index + 1} 缺少对象类型的参数")
            Triple(name, handler, arguments)
        }

        val observations = mutableListOf<JsonObject>()
        for ((index, entry) in validated.withIndex()) {
            val (name, handler, arguments) = entry
            // The parent call receipt and each child tool receipt have different call identities.
            val attrs = original.attributes + (
                "call_id" to (original.attributes["call_id"]?.toString().orEmpty()
                    .ifBlank { "code-mode" } + "/step-${index + 1}")
            )
            val child = original.copy(attributes = attrs, expectedRegistration = handler)
            val outcome = withTimeoutOrNull(STEP_TIMEOUT_MILLIS) {
                liveTools.executeTracked(name, arguments, arguments.toString(), child)
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
        }
        return receipt(observations, "completed", null)
    }

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
        const val MAX_RESULT_CHARS = 4_000
        const val STEP_TIMEOUT_MILLIS = 90_000L
    }
}
