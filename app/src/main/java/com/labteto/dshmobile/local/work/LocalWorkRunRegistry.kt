package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalWorkRunBinding
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Work-owned registry for session-bound foreground runs.
 *
 * The registry is the single in-memory owner of active Work bindings. Engine and shared
 * capabilities may query it, but they do not own a second map or lifecycle.
 */
@Singleton
class LocalWorkRunRegistry @Inject constructor() {
    private val bindings = java.util.concurrent.ConcurrentHashMap<String, LocalWorkRunBinding>()

    internal operator fun get(sessionId: String): LocalWorkRunBinding? = bindings[sessionId]

    internal fun state(sessionId: String): LocalHarnessState? = bindings[sessionId]?.state?.value

    internal fun live(sessionId: String): LocalWorkRunBinding? =
        bindings[sessionId]?.takeIf { it.job?.isCompleted == false }

    internal fun attach(binding: LocalWorkRunBinding): LocalWorkRunBinding? =
        bindings.put(binding.sessionId, binding)

    internal fun detach(sessionId: String): LocalWorkRunBinding? = bindings.remove(sessionId)

    internal fun detach(binding: LocalWorkRunBinding): Boolean =
        bindings.remove(binding.sessionId, binding)

    internal fun detachAll(sessionIds: Set<String>): List<LocalWorkRunBinding> =
        sessionIds.mapNotNull(bindings::remove)

    internal fun anyLive(): Boolean = bindings.values.any { it.job?.isCompleted == false }

    internal fun forEachBinding(block: (LocalWorkRunBinding) -> Unit) {
        bindings.values.toList().forEach(block)
    }

    internal fun forEachEntry(block: (String, LocalWorkRunBinding) -> Unit) {
        bindings.entries.toList().forEach { (sessionId, binding) -> block(sessionId, binding) }
    }
}
