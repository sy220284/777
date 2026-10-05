package com.labteto.dshmobile.local.work

import android.content.Context
import com.labteto.dshmobile.harness.resource.HarnessResourceSnapshot
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalJobInfo
import com.labteto.dshmobile.local.LocalWorkRunBinding
import com.labteto.dshmobile.local.projectJobSnapshotToSessionStates
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.toLocalHarnessResourceState
import com.labteto.dshmobile.local.syncForegroundJobs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.update

/**
 * Work-owned registry for session-bound foreground runs.
 *
 * The registry is the single in-memory owner of active Work bindings. It observes shared Runtime
 * resource and background-job facts in the allowed Work -> Shared Capability direction; Runtime
 * never imports Work.
 */
@Singleton
class LocalWorkRunRegistry internal constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val appContext: Context?,
) {
    @Inject
    internal constructor(
        runtimeStateStore: LocalRuntimeStateStore,
        @ApplicationContext context: Context,
    ) : this(runtimeStateStore, context.applicationContext)

    internal constructor(runtimeStateStore: LocalRuntimeStateStore) : this(runtimeStateStore, null)

    private val bindings = java.util.concurrent.ConcurrentHashMap<String, LocalWorkRunBinding>()

    init {
        runtimeStateStore.observeResourceSnapshots(::projectResourceSnapshot)
        runtimeStateStore.observeJobSnapshots(::projectJobSnapshot)
    }

    internal operator fun get(sessionId: String): LocalWorkRunBinding? = bindings[sessionId]

    internal fun state(sessionId: String): LocalHarnessState? = bindings[sessionId]?.state?.value

    internal fun live(sessionId: String): LocalWorkRunBinding? =
        bindings[sessionId]?.takeIf { it.job?.isCompleted == false }

    internal fun attach(binding: LocalWorkRunBinding): LocalWorkRunBinding? {
        val previous = bindings.put(binding.sessionId, binding)
        projectResourceSnapshot(binding, runtimeStateStore.resourceSnapshot())
        projectJobSnapshotToSessionStates(
            runtimeStateStore.jobManager.snapshotInfos(),
            runtimeStateStore.mutableState,
            this,
        )
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

    private fun projectJobSnapshot(snapshot: List<LocalJobInfo>) {
        projectJobSnapshotToSessionStates(snapshot, runtimeStateStore.mutableState, this)
        appContext?.let { context ->
            syncForegroundJobs(context, snapshot) { message ->
                runtimeStateStore.mutableState.update { it.copy(error = message) }
            }
        }
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
