package com.labteto.dshmobile.harness.agent

import java.util.concurrent.atomic.AtomicReference

data class AgentModelRoute(
    val model: String,
    val baseUrl: String,
) {
    init {
        require(model.isNotBlank()) { "模型不能为空" }
        require(baseUrl.isNotBlank()) { "模型地址不能为空" }
    }
}

data class AgentPermissionScope(
    val allowMutation: Boolean,
    val planMode: Boolean = false,
)

/**
 * Immutable ownership plus small run-local mutable state for one Agent execution.
 *
 * Product surfaces are intentionally absent. Chat and Work may assemble different context above
 * this boundary, but they share the same model route, permissions, tool view and cancellation facts.
 */
class AgentRunContext(
    val sessionId: String,
    val route: AgentModelRoute,
    val permissions: AgentPermissionScope,
    initialOptionalTools: Set<String> = emptySet(),
) {
    init {
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
    }

    private val optionalTools = linkedSetOf<String>().apply {
        initialOptionalTools.filterTo(this) { it.isNotBlank() }
    }
    private val cancellationReason = AtomicReference<String?>(null)

    @Synchronized
    fun enableOptionalTools(names: Collection<String>) {
        optionalTools += names.filter(String::isNotBlank)
    }

    @Synchronized
    fun optionalTools(): Set<String> = optionalTools.toSet()

    fun cancel(reason: String) {
        cancellationReason.compareAndSet(null, reason.ifBlank { "cancelled" })
    }

    val isCancelled: Boolean get() = cancellationReason.get() != null

    val cancelReason: String? get() = cancellationReason.get()

    companion object {
        const val TOOL_CONTEXT_ATTRIBUTE = "agent_run_context"
    }
}
