package com.labteto.dshmobile.local

/**
 * Product-surface capability policy layered on top of the shared Harness runtime.
 *
 * Chat and Work share the same AgentLoop, model transport, context management, event log and
 * lifecycle. Only Work exposes executable tools. Single chat is a pure primary-model conversation;
 * group chat keeps its separate multi-character orchestration.
 */
internal data class LocalAgentRunPolicy(
    val toolsEnabled: Boolean,
    val allowToolExecution: Boolean,
    val imageFallbackToVisionTool: Boolean,
)

internal fun localAgentRunPolicy(usageMode: LocalUsageMode): LocalAgentRunPolicy = when (usageMode) {
    LocalUsageMode.CHAT -> LocalAgentRunPolicy(
        toolsEnabled = false,
        allowToolExecution = false,
        imageFallbackToVisionTool = false,
    )
    LocalUsageMode.WORK -> LocalAgentRunPolicy(
        toolsEnabled = true,
        allowToolExecution = true,
        imageFallbackToVisionTool = true,
    )
}
