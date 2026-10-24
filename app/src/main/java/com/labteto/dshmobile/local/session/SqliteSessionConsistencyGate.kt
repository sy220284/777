package com.labteto.dshmobile.local.session

import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Process-wide quiescence gate for the two SQLite databases.
 * Writers hold a shared lease through SQLite COMMIT; WAL-aware export or VACUUM
 * holds the exclusive lease until both database files have been copied.
 * Reentrant mutation leases allow nested recovery-receipt writes.
 * Cross-process writers require an additional interprocess lease.
 */
internal object SqliteSessionConsistencyGate {
    private val lock = ReentrantReadWriteLock(true)
    fun acquireMutation(): Lock = lock.readLock().also { it.lock() }
    fun acquireExclusive(): Lock = lock.writeLock().also { it.lock() }
}
