package com.labteto.dshmobile.harness.session

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex

/** Identity of one ownership interval. Epochs never repeat within a process incarnation. */
data class SessionExecutionIdentity(
    val sessionId: String,
    val incarnation: String,
    val epoch: Long,
)

/** Neutral runtime policy; Feature names and business state do not belong to this registry. */
data class SessionExecutionOwner(val name: String, val acceptsInput: Boolean)

data class SessionExecutionAdmission(val ownerActive: Boolean, val inputBlocked: Boolean)

class SessionExecutionLease internal constructor(
    val identity: SessionExecutionIdentity,
    val owner: SessionExecutionOwner,
    private val registry: SessionExecutionRegistry,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun requireCurrent(sessionId: String = identity.sessionId) {
        check(sessionId == identity.sessionId && !closed.get() && registry.isCurrent(this)) {
            "会话执行所有权已失效或不属于目标会话：$sessionId"
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) registry.release(this)
    }
}

/** Propagates exact ownership through model/tool child coroutines, never through mutable UI state. */
class SessionExecutionContext(val lease: SessionExecutionLease) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionExecutionContext>
}

/**
 * Shared Session is the sole owner of admission and handoff. Reservations include suspended
 * acquirers, so deletion/maintenance cannot be bypassed during a mutex handoff. Empty entries
 * are removed; the process epoch counter retains identity without retaining old sessions.
 */
class SessionExecutionRegistry {
    private class Entry {
        val mutex = Mutex()
        var reservations = 0
        var lease: SessionExecutionLease? = null
    }

    private val incarnation = UUID.randomUUID().toString()
    private var epoch = 0L
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun hasLiveOwner(sessionId: String): Boolean =
        entries[sessionId]?.reservations?.let { it > 0 } == true

    @Synchronized
    fun hasAnyLiveOwner(): Boolean = entries.values.any { it.reservations > 0 }

    @Synchronized
    fun retainedSessionCount(): Int = entries.size

    @Synchronized
    fun inputAdmission(sessionId: String): SessionExecutionAdmission {
        val entry = entries[sessionId]
        // A reserved successor may not have resumed after the predecessor releases
        // its mutex. That interval is NOT idle: repair/send must not race its claim.
        val active = (entry?.reservations ?: 0) > 0
        return SessionExecutionAdmission(active, active && entry?.lease?.owner?.acceptsInput != true)
    }

    fun tryAcquire(sessionId: String, owner: SessionExecutionOwner): SessionExecutionLease? {
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
        synchronized(this) {
            val entry = entries.getOrPut(sessionId, ::Entry)
            if (entry.reservations > 0 || !entry.mutex.tryLock()) return null
            entry.reservations = 1
            return claim(sessionId, owner, entry)
        }
    }

    suspend fun acquire(sessionId: String, owner: SessionExecutionOwner): SessionExecutionLease {
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
        val entry = synchronized(this) {
            entries.getOrPut(sessionId, ::Entry).also { it.reservations++ }
        }
        try {
            entry.mutex.lock()
        } catch (error: Throwable) {
            releaseReservation(sessionId, entry)
            throw error
        }
        return synchronized(this) { claim(sessionId, owner, entry) }
    }

    suspend fun acquireAll(
        sessionIds: Collection<String>,
        owner: SessionExecutionOwner,
    ): List<SessionExecutionLease> {
        // A bulk caller must never silently drop a malformed identity: partial
        // admission could delete/migrate only a subset of its requested sessions.
        require(sessionIds.none(String::isBlank)) { "批量会话编号不能为空" }
        val orderedSessions = sessionIds.distinct().sorted()
        val leases = mutableListOf<SessionExecutionLease>()
        try {
            orderedSessions.forEach {
                leases += acquire(it, owner)
            }
            return leases
        } catch (error: Throwable) {
            leases.asReversed().forEach(SessionExecutionLease::close)
            throw error
        }
    }

    fun requireOwner(lease: SessionExecutionLease, sessionId: String, owner: SessionExecutionOwner) {
        lease.requireCurrent(sessionId)
        check(lease.owner == owner && isCurrent(lease)) { "会话执行所有者或注册表不匹配" }
    }

    @Synchronized
    internal fun isCurrent(lease: SessionExecutionLease): Boolean =
        entries[lease.identity.sessionId]?.lease === lease

    private fun claim(
        sessionId: String,
        owner: SessionExecutionOwner,
        entry: Entry,
    ): SessionExecutionLease {
        epoch = Math.incrementExact(epoch)
        return SessionExecutionLease(SessionExecutionIdentity(sessionId, incarnation, epoch), owner, this)
            .also { entry.lease = it }
    }

    internal fun release(lease: SessionExecutionLease) {
        val sessionId = lease.identity.sessionId
        val entry = synchronized(this) {
            val current = checkNotNull(entries[sessionId])
            check(current.lease === lease) { "只能释放当前会话执行所有权" }
            current.lease = null
            current
        }
        entry.mutex.unlock()
        releaseReservation(sessionId, entry)
    }

    @Synchronized
    private fun releaseReservation(sessionId: String, entry: Entry) {
        entry.reservations--
        if (entry.reservations == 0) entries.remove(sessionId)
    }
}
