package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import org.junit.Assert.*
import org.junit.Test

class LocalForegroundTurnWakeCoordinatorTest {
    @Test
    fun failedStarterDoesNotStrandTheSessionOwner() {
        val id = "wake-failed-starter"
        val lease = requireNotNull(LocalSessionRuntimeRegistry.tryAcquire(id, LocalSessionRuntimeKind.FOREGROUND))
        val failure = IllegalStateException("inbox disk failure")
        val result = runCatching { handoffForegroundSessionLease(lease) { throw failure } }
        assertSame(failure, result.exceptionOrNull())
        val maintenance = LocalSessionRuntimeRegistry.tryAcquire(id, LocalSessionRuntimeKind.MAINTENANCE)
        assertNotNull(maintenance)
        maintenance?.close()
    }

    @Test
    fun emptyInboxReturnsTheLeaseImmediately() {
        val id = "wake-empty-inbox"
        val lease = requireNotNull(LocalSessionRuntimeRegistry.tryAcquire(id, LocalSessionRuntimeKind.FOREGROUND))
        assertNull(handoffForegroundSessionLease(lease) { null })
        val next = LocalSessionRuntimeRegistry.tryAcquire(id, LocalSessionRuntimeKind.FOREGROUND)
        assertNotNull(next)
        next?.close()
    }
}
