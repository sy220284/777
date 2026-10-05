package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.local.model.LocalToolCall
import java.util.UUID
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class LocalBuiltinPlugin(
    private val execute: suspend (LocalToolCall, Boolean, String?) -> String,
) : HarnessPlugin {
        override val id = "android-local-builtins"

        override suspend fun install(context: HarnessContext) {
            LocalToolCatalog.specs.forEach { element ->
                val schema = element.jsonObject
                val function = schema["function"]?.jsonObject ?: return@forEach
                val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                context.tools.register(
                    HarnessTool(
                        name = name,
                        schema = schema,
                        access = LocalToolPolicy.access(name),
                        approvalPolicy = LocalToolPolicy.approval(name),
                        exposure = LocalToolPolicy.exposure(name),
                        metadata = LocalToolPolicy.metadata(name),
                        executor = HarnessToolExecutor { toolContext, input, rawArguments ->
                            val callId = toolContext.attributes["call_id"] as? String
                                ?: "registry-" + UUID.randomUUID().toString().take(8)
                            ToolResult(
                                execute(
                                    LocalToolCall(
                                        id = callId,
                                        name = name,
                                        arguments = input,
                                        rawArguments = rawArguments,
                                    ),
                                    toolContext.allowMutation,
                                    toolContext.sessionId,
                                ),
                            )
                        },
                    ),
                )
            }
        }

        override suspend fun uninstall(context: HarnessContext) {
            LocalToolCatalog.specs.forEach { element ->
                element.jsonObject["function"]?.jsonObject
                    ?.get("name")?.jsonPrimitive?.contentOrNull
                    ?.let(context.tools::unregister)
            }
        }
    }