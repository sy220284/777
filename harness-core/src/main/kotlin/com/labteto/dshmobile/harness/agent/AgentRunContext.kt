package com.labteto.dshmobile.harness.agent

/** Model transport selected for one immutable Agent run. */
enum class AgentModelProtocol {
    OPENAI_CHAT,
    ANTHROPIC_MESSAGES,
    RESPONSES,
}

data class AgentModelRoute(
    val provider: String,
    val baseUrl: String,
    val model: String,
    val protocol: AgentModelProtocol = AgentModelProtocol.OPENAI_CHAT,
)

/**
 * Immutable tool visibility for one Agent run.
 *
 * null means the platform adapter owns dynamic discovery for this run; a concrete set is enforced
 * by the core loop and cannot be widened by child/sibling work.
 */
data class AgentToolView(
    val names: Set<String>? = null,
) {
    fun allows(name: String): Boolean = names == null || name in names

    companion object {
        val UNRESTRICTED = AgentToolView(null)
    }
}

data class AgentPermissionScope(
    val allowMutation: Boolean = true,
    val approvalScope: String = "run",
)

data class AgentResourceBudget(
    val maxSteps: Int? = null,
    val maxModelRequests: Int? = null,
    val maxParallelTools: Int? = null,
)

fun interface AgentCancellation {
    fun throwIfCancelled()
}

/**
 * Stable run-scoped state shared by foreground, subagent and background Agent loops.
 *
 * Product surfaces may build different prompts/UI, but they do not own a second execution model.
 */
data class AgentRunContext(
    val runId: String,
    val sessionId: String,
    val lineageId: String? = null,
    val modelRoute: AgentModelRoute,
    val toolView: AgentToolView = AgentToolView.UNRESTRICTED,
    val permissions: AgentPermissionScope = AgentPermissionScope(),
    val resources: AgentResourceBudget = AgentResourceBudget(),
    val attributes: Map<String, String> = emptyMap(),
    val cancellation: AgentCancellation = AgentCancellation { },
)
