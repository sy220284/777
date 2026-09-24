package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.HarnessJobManager
import com.labteto.dshmobile.harness.jobs.JobSnapshot
import kotlinx.coroutines.CoroutineScope

/** Android projection adapter for the process-agnostic core job controller. */
internal class LocalJobManager(
    scope: CoroutineScope,
    store: LocalPersistentJobStore? = null,
    onChanged: (List<LocalJobInfo>) -> Unit,
) {
    private val delegate = HarnessJobManager(
        scope = scope,
        onChanged = { jobs ->
            onChanged(jobs.map { LocalJobInfo(it.id, it.label, it.status) })
        },
        initialSnapshots = store?.read().orEmpty(),
        onSnapshotsChanged = { snapshots -> store?.write(snapshots) },
    )

    fun start(label: String, block: suspend (String, (String) -> Unit) -> String): String =
        delegate.start(label, block)

    fun startPersistent(
        label: String,
        resumeKind: String,
        resumePayload: String,
        block: suspend (String, (String) -> Unit) -> String,
    ): String = delegate.startPersistent(label, resumeKind, resumePayload, block)

    fun interruptedSnapshots(): List<JobSnapshot> = delegate.interruptedSnapshots()

    fun availableSlots(): Int = delegate.availableSlots()

    fun failInterrupted(id: String, detail: String): String = delegate.failInterrupted(id, detail)

    fun resumePersistent(
        id: String,
        block: suspend (String, (String) -> Unit) -> String,
    ): String = delegate.resumePersistent(id, block)

    fun snapshotInfos(): List<LocalJobInfo> = delegate.snapshots().map {
        LocalJobInfo(it.id, it.label, it.status)
    }

    fun list(): String = delegate.list()

    fun listAgents(): String = delegate.listAgents()

    fun output(id: String): String = delegate.output(id)

    fun kill(id: String): String = delegate.kill(id)

    fun send(id: String, message: String): String = delegate.send(id, message)

    fun drainMessages(id: String): List<String> = delegate.drainMessages(id)

    fun stopAll() = delegate.stopAll()

    suspend fun stopAllAndJoin() = delegate.stopAllAndJoin()

    suspend fun stopNonPersistentAndJoin() = delegate.stopNonPersistentAndJoin()
}
