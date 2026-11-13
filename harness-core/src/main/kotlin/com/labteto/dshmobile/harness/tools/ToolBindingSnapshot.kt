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
    private val codeModeChildren: Map<String, HarnessTool> = emptyMap(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolBindingSnapshot>

    fun registration(name: String): HarnessTool? = bindings[name]
    /** Frozen at model sampling; not part of the public tool list or a durable permission grant. */
    internal fun codeModeChild(name: String): HarnessTool? = codeModeChildren[name]
}

/** A first-party Code Mode executor may enter exactly one frozen child registration. */
internal class CodeModeStepScope(
    val snapshot: ToolBindingSnapshot,
    val registration: HarnessTool,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CodeModeStepScope>
}
