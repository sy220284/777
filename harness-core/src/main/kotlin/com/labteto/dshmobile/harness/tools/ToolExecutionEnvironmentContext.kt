package com.labteto.dshmobile.harness.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonObject

/** Selected by the execution backend; ToolRegistry still owns admission and the effect receipt. */
class ToolExecutionEnvironmentContext(
    val execute: suspend (HarnessTool, ToolContext, JsonObject, String) -> ToolResult,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolExecutionEnvironmentContext>
}
