package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.harness.session.SessionExecutionAdmission
import com.labteto.dshmobile.harness.session.SessionExecutionContext
import com.labteto.dshmobile.harness.session.SessionExecutionLease
import com.labteto.dshmobile.harness.session.SessionExecutionOwner
import com.labteto.dshmobile.harness.session.SessionExecutionRegistry
import kotlinx.coroutines.withContext

internal enum class LocalSessionRuntimeKind {
    FOREGROUND,
    AUTOMATION_CHAT,
    AUTOMATION_WORK,
    SESSION_DELETE,
    MAINTENANCE,
}

internal typealias LocalSessionInputAdmission = SessionExecutionAdmission
internal typealias LocalSessionRuntimeLease = SessionExecutionLease

/** Android compatibility adapter; ownership facts and scheduling live only in Shared Session. */
internal object LocalSessionRuntimeRegistry {
    private val executions = SessionExecutionRegistry()

    fun hasLiveOwner(sessionId: String): Boolean = executions.hasLiveOwner(sessionId)
    internal fun hasAnyLiveOwner(): Boolean = executions.hasAnyLiveOwner()
    internal fun inputAdmission(sessionId: String): LocalSessionInputAdmission =
        executions.inputAdmission(sessionId)
    internal fun retainedSessionCount(): Int = executions.retainedSessionCount()

    fun submitWhenIdle(sessionId: String, submit: () -> Unit): Boolean {
        val lease = tryAcquire(sessionId, LocalSessionRuntimeKind.MAINTENANCE) ?: return false
        return try {
            submit()
            true
        } finally {
            lease.close()
        }
    }

    suspend fun <T> withOwner(
        sessionId: String,
        kind: LocalSessionRuntimeKind,
        block: suspend (String) -> T,
    ): T = withOwner(sessionId, kind, preownedLease = null, block)

    suspend fun <T> withOwner(
        sessionId: String,
        kind: LocalSessionRuntimeKind,
        preownedLease: LocalSessionRuntimeLease?,
        block: suspend (String) -> T,
    ): T {
        val lease = preownedLease ?: acquire(sessionId, kind)
        // Validate before taking responsibility for teardown. A wrong caller cannot close
        // another operation's reservation merely by passing its lease here.
        executions.requireOwner(lease, sessionId, kind.owner())
        try {
            return withContext(SessionExecutionContext(lease)) { block(sessionId) }
        } finally {
            lease.close()
        }
    }

    fun tryAcquire(sessionId: String, kind: LocalSessionRuntimeKind): LocalSessionRuntimeLease? =
        executions.tryAcquire(sessionId, kind.owner())

    suspend fun acquire(sessionId: String, kind: LocalSessionRuntimeKind): LocalSessionRuntimeLease =
        executions.acquire(sessionId, kind.owner())

    suspend fun acquireAll(
        sessionIds: Collection<String>,
        kind: LocalSessionRuntimeKind,
    ): List<LocalSessionRuntimeLease> = executions.acquireAll(sessionIds, kind.owner())

    private fun LocalSessionRuntimeKind.owner() = SessionExecutionOwner(
        name = name,
        acceptsInput = this == LocalSessionRuntimeKind.FOREGROUND ||
            this == LocalSessionRuntimeKind.AUTOMATION_CHAT ||
            this == LocalSessionRuntimeKind.AUTOMATION_WORK,
    )
}
