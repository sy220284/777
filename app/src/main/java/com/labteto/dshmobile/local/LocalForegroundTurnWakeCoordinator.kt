package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatComposition
import com.labteto.dshmobile.local.work.LocalRuntimeOwnershipPolicy
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.work.LocalWorkComposition
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job

/**
 * App-level wake router for a recovered foreground inbox.
 *
 * Chat and Work each own how a turn starts. This coordinator only selects the product owner from
 * the already-authoritative visible usage mode and never executes a product turn itself.
 */
@Singleton
internal class LocalForegroundTurnWakeCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val chat: LocalChatComposition,
    private val work: LocalWorkComposition,
    private val workRuns: LocalWorkRunRegistry,
) {
    internal fun startNextIfIdle(): Job? {
        val snapshot = runtimeStateStore.state.value
        if (snapshot.usageMode == LocalUsageMode.CHAT) {
            return chat.queue.startNextIfIdle()
        }
        val handle = runtimeStateStore.foregroundRunHandle
        return synchronized(handle.lock) {
            val sessionId = runtimeStateStore.currentSessionId
            if (
                !LocalRuntimeOwnershipPolicy.allowVisibleQueuedTurn(
                    sessionTransitioning = runtimeStateStore.sessionTransitioning,
                    visibleRunActive = handle.job?.isCompleted == false,
                    liveWorkOwner = workRuns.live(sessionId) != null,
                )
            ) return@synchronized null

            val lease = LocalSessionRuntimeRegistry.tryAcquire(
                sessionId,
                LocalSessionRuntimeKind.FOREGROUND,
            ) ?: return@synchronized null
            handoffForegroundSessionLease(lease) { work.turnStarter.startNextResumed(lease) }
        }
    }
}

/** A turn owns the lease only after the lazy Job is returned successfully. */
internal fun handoffForegroundSessionLease(
    lease: LocalSessionRuntimeLease,
    start: () -> Job?,
): Job? = try {
    start().also { if (it == null) lease.close() }
} catch (error: Throwable) {
    lease.close()
    throw error
}
