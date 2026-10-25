package com.labteto.dshmobile.harness.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Immutable handlers advertised by one sampling request. Like Codex StepContext.tool_router,
 * this binds execution to the sampled contract; a later registration is a different authority.
 * It is request-local and is never a second registry or a persisted permission grant.
 */
class ToolBindingSnapshot internal constructor(
    private val bindings: Map<String, HarnessTool>,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolBindingSnapshot>

    fun registration(name: String): HarnessTool? = bindings[name]
}
