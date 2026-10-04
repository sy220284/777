package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.resource.HarnessResourceSnapshot
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalWorkRunBinding
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.toLocalHarnessResourceState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.update

/**
 * Work-owned registry for session-bound foreground runs.
 *
 * The registry is the single in-memory owner of active Work bindings. It observes shared Runtime
 * resource facts in the allowed Work -> Shared Capability direction; Runtime never imports Work.
 */
@Singleton
class LocalWorkRunRegistry @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    private val bindings = java.util.concurrent.ConcurrentHashMap<String, LocalWorkRunBinding>()

    init {
        runtimeStateStore.observeResourceSnapshots(::projectResourceSnapshot)
    }

    internal operator fun get(sessionId: String): LocalWorkRunBinding? = bindings[sessionId]

    internal fun state(sessionId: String): LocalHarnessState? = bindings[sessionId]?.state?.value

    internal fun live(sessionId: String): LocalWorkRunBinding? =
        bindings[sessionId]?.takeIf { it.job?.isCompleted == false }

    internal fun attach(binding: LocalWorkRunBinding): LocalWorkRunBinding? {
        val previous = bindings.put(binding.sessionId, binding)
        projectResourceSnapshot(binding, runtimeStateStore.resourceSnapshot())
        return previous
    }

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

    private fun projectResourceSnapshot(snapshot: HarnessResourceSnapshot) {
        bindings.values.toList().forEach { binding ->
            projectResourceSnapshot(binding, snapshot)
        }
    }

    private fun projectResourceSnapshot(
        binding: LocalWorkRunBinding,
        snapshot: HarnessResourceSnapshot,
    ) {
        binding.state.update { current ->
            current.copy(
                kernel = current.kernel.copy(
                    resources = snapshot.toLocalHarnessResourceState(current.usageMode),
                    contextBudgetChars = runtimeStateStore.contextBudgetCharsFor(current, snapshot),
                ),
            )
        }
    }
}
