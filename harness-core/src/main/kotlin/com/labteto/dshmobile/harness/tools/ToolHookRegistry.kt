package com.labteto.dshmobile.harness.tools

import kotlinx.serialization.json.JsonObject

/**
 * Registry-owned extension point for policy and audit plugins.
 * Hooks are installed by trusted application/plugin composition, never from model output.
 * Each invocation captures a stable ordered snapshot; changes during approval revoke admission.
 */
interface ToolLifecycleHook {
    suspend fun beforeUse(request: ToolPreUseRequest): ToolPreUseDecision = ToolPreUseDecision.Allow
    suspend fun afterUse(event: ToolPostUseEvent) = Unit
}

data class ToolPreUseRequest(
    val sessionId: String?,
    val tool: HarnessTool,
    val input: JsonObject,
)

sealed interface ToolPreUseDecision {
    data object Allow : ToolPreUseDecision
    data class Deny(val reason: String) : ToolPreUseDecision {
        init { require(reason.isNotBlank() && reason.length <= 512) }
    }
}

data class ToolPostUseEvent(
    val sessionId: String?,
    val tool: HarnessTool,
    val result: ToolResult,
)

data class ToolHookSubscription(
    val id: String,
    val hook: ToolLifecycleHook,
    /** Empty matches all tools; nonempty set requires an exact name. */
    val toolNames: Set<String> = emptySet(),
) {
    init {
        require(Regex("[A-Za-z0-9._-]{1,96}").matches(id)) { "非法 Hook ID：$id" }
        require(toolNames.size <= 128) { "Hook 工具匹配集合过大" }
        require(toolNames.all { Regex("[A-Za-z0-9_-]{1,128}").matches(it) }) {
            "Hook 匹配工具名称非法"
        }
    }

    fun matches(name: String): Boolean = toolNames.isEmpty() || name in toolNames
}

data class ToolHookSnapshot internal constructor(
    val generation: Long,
    val subscriptions: List<ToolHookSubscription>,
)

/** Copy-on-write catalog shared by forked ToolRegistry instances. */
class ToolHookRegistry {
    private val lock = Any()
    private val subscriptions = linkedMapOf<String, ToolHookSubscription>()
    private var generation = 0L

    fun register(subscription: ToolHookSubscription) = synchronized(lock) {
        require(subscription.id !in subscriptions) { "Hook 已注册：" + subscription.id }
        subscriptions[subscription.id] = subscription
        generation++
    }

    fun unregister(id: String): Boolean = synchronized(lock) {
        if (subscriptions.remove(id) == null) return@synchronized false
        generation++
        true
    }

    fun ids(): List<String> = synchronized(lock) { subscriptions.keys.toList() }

    fun snapshot(toolName: String): ToolHookSnapshot = synchronized(lock) {
        ToolHookSnapshot(generation, subscriptions.values.filter { it.matches(toolName) })
    }

    fun isCurrent(snapshot: ToolHookSnapshot): Boolean = synchronized(lock) {
        generation == snapshot.generation
    }
}
