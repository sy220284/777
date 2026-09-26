package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.plugin.*
import com.labteto.dshmobile.harness.tools.*
import java.util.UUID
import kotlinx.serialization.json.*

internal class LocalBuiltinPlugin(
    private val execute: suspend (LocalToolCall, Boolean, ToolContext) -> String,
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
                                    toolContext,
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
