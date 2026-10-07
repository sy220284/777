package com.labteto.dshmobile.local.jobs

import com.labteto.dshmobile.harness.jobs.HarnessJobManager
import com.labteto.dshmobile.harness.jobs.JobInboxMessage
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
            onChanged(jobs.map { LocalJobInfo(it.id, it.label, it.status, it.ownerId) })
        },
        initialSnapshots = store?.read().orEmpty(),
        onSnapshotsChanged = { snapshots -> store?.write(snapshots) },
    )

    fun start(
        label: String,
        expectedDurationMillis: Long? = null,
        ownerSessionId: String? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): String = delegate.start(label, expectedDurationMillis, ownerSessionId, block)

    fun startPersistent(
        label: String,
        resumeKind: String,
        resumePayload: String,
        ownerSessionId: String? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): String = delegate.startPersistent(label, resumeKind, resumePayload, ownerSessionId, block)

    fun interruptedSnapshots(): List<JobSnapshot> = delegate.interruptedSnapshots()

    fun availableSlots(): Int = delegate.availableSlots()

    fun failInterrupted(id: String, detail: String): String = delegate.failInterrupted(id, detail)

    fun resumePersistent(
        id: String,
        ownerSessionId: String? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): String = delegate.resumePersistent(id, ownerSessionId, block)

    fun snapshotInfos(): List<LocalJobInfo> = delegate.snapshots().map {
        LocalJobInfo(it.id, it.label, it.status, it.ownerId)
    }

    fun list(ownerSessionId: String? = null): String = delegate.list(ownerSessionId)

    fun listAgents(ownerSessionId: String? = null): String = delegate.listAgents(ownerSessionId)

    fun output(id: String, ownerSessionId: String? = null): String = delegate.output(id, ownerSessionId)

    fun kill(id: String, ownerSessionId: String? = null): String = delegate.kill(id, ownerSessionId)

    fun send(id: String, message: String, ownerSessionId: String? = null): String =
        delegate.send(id, message, ownerSessionId)

    fun peekMessages(id: String): List<JobInboxMessage> = delegate.peekMessages(id)

    fun acknowledgeMessages(
        id: String,
        messageIds: Set<String>,
        ownerSessionId: String? = null,
    ) = delegate.acknowledgeMessages(id, messageIds, ownerSessionId)

    fun drainMessages(id: String): List<String> = delegate.drainMessages(id)

    fun stopAll() = delegate.stopAll()

    suspend fun stopAllAndJoin() = delegate.stopAllAndJoin()

    suspend fun stopNonPersistentAndJoin() = delegate.stopNonPersistentAndJoin()

    suspend fun stopOwnedNonPersistentAndJoin(sessionIds: Set<String>) =
        delegate.stopOwnedNonPersistentAndJoin(sessionIds)

    suspend fun removeOwnedAndJoin(sessionIds: Set<String>) = delegate.removeOwnedAndJoin(sessionIds)
}
