package com.labteto.dshmobile.local.runtime

import android.content.Context
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.jobs.LocalPersistentJobStore
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * Shared Runtime owner for process-wide background jobs.
 *
 * Product features observe job snapshots in the allowed Feature -> Shared Capability direction.
 * The owner never imports Feature state or presentation code.
 */
internal class LocalRuntimeJobOwner internal constructor(
    scope: CoroutineScope,
    store: LocalPersistentJobStore?,
    maxConcurrentJobs: Int = 4,
) {
    private val observers = CopyOnWriteArrayList<(List<LocalJobInfo>) -> Unit>()

    internal val manager = LocalJobManager(
        scope = scope,
        store = store,
        maxConcurrentJobs = maxConcurrentJobs,
        onChanged = ::publish,
    )

    internal fun observe(observer: (List<LocalJobInfo>) -> Unit) {
        observers += observer
        runCatching { observer(manager.snapshotInfos()) }
            .onFailure { AppLog.failure("LocalRuntimeJobOwner", "observe_job_snapshot", it) }
    }

    private fun publish(snapshot: List<LocalJobInfo>) {
        observers.forEach { observer ->
            runCatching { observer(snapshot) }
                .onFailure { AppLog.failure("LocalRuntimeJobOwner", "publish_job_snapshot", it) }
        }
    }

    companion object {
        internal fun inMemory(maxConcurrentJobs: Int = 4): LocalRuntimeJobOwner =
            LocalRuntimeJobOwner(
                scope = processScope(),
                store = null,
                maxConcurrentJobs = maxConcurrentJobs,
            )

        internal fun persistent(
            context: Context,
            json: Json,
            maxConcurrentJobs: Int,
        ): LocalRuntimeJobOwner =
            LocalRuntimeJobOwner(
                scope = processScope(),
                store = LocalPersistentJobStore(
                    file = File(context.filesDir, "local-harness/jobs.json"),
                    json = json,
                ),
                maxConcurrentJobs = maxConcurrentJobs,
            )

        private fun processScope(): CoroutineScope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO +
                    CoroutineExceptionHandler { _, throwable ->
                        AppLog.error("LocalRuntimeJobOwner", "background job coroutine failure", throwable)
                    },
            )
    }
}
