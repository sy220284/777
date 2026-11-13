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

/**
 * Plugins install against staged HarnessContext instances. Hook changes follow the same
 * publish/rollback lifecycle as tool registrations; a failed plugin never changes live policy.
 * After successful publish the staged registry is rebound so retained contexts see the live view.
 */
class ToolHookRegistry {
    private class State(
        val subscriptions: LinkedHashMap<String, ToolHookSubscription> = linkedMapOf(),
        var generation: Long = 0L,
    ) {
        val lock = Any()
    }

    @Volatile private var state = State()

    fun register(subscription: ToolHookSubscription) {
        val current = state
        synchronized(current.lock) {
            require(subscription.id !in current.subscriptions) { "Hook 已注册：" + subscription.id }
            current.subscriptions[subscription.id] = subscription
            current.generation++
        }
    }

    fun unregister(id: String): Boolean {
        val current = state
        return synchronized(current.lock) {
            if (current.subscriptions.remove(id) == null) return@synchronized false
            current.generation++
            true
        }
    }

    fun ids(): List<String> {
        val current = state
        return synchronized(current.lock) { current.subscriptions.keys.toList() }
    }

    fun subscriptions(): List<ToolHookSubscription> {
        val current = state
        return synchronized(current.lock) { current.subscriptions.values.toList() }
    }

    fun snapshot(toolName: String): ToolHookSnapshot {
        val current = state
        return synchronized(current.lock) {
            ToolHookSnapshot(current.generation, current.subscriptions.values.filter {
                it.matches(toolName)
            })
        }
    }

    fun isCurrent(snapshot: ToolHookSnapshot): Boolean {
        val current = state
        return synchronized(current.lock) { current.generation == snapshot.generation }
    }

    fun fork(): ToolHookRegistry {
        val original = state
        return synchronized(original.lock) {
            ToolHookRegistry().also { copy ->
                copy.state = State(
                    linkedMapOf<String, ToolHookSubscription>().apply {
                        putAll(original.subscriptions)
                    },
                    original.generation,
                )
            }
        }
    }

    fun restore(snapshot: List<ToolHookSubscription>) {
        require(snapshot.map { it.id }.distinct().size == snapshot.size) {
            "Hook 快照存在重复规则"
        }
        val current = state
        synchronized(current.lock) {
            val proposed = snapshot.associateBy(ToolHookSubscription::id)
            // Hook ordering is executable policy. LinkedHashMap equality ignores order;
            // reordering must invalidate an approval captured under the old sequence.
            if (current.subscriptions.entries.toList() == proposed.entries.toList()) return@synchronized
            current.subscriptions.clear()
            current.subscriptions.putAll(proposed)
            current.generation++
        }
    }

    fun publishTo(destination: ToolHookRegistry) {
        val outgoing = subscriptions()
        destination.restore(outgoing)
        // A retained install context must continue to modify the committed registry, not a
        // stale staged copy. All subsequent actions see the same version-fenced policy state.
        state = destination.state
    }
}
