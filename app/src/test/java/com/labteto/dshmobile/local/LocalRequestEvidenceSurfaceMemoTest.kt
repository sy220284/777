package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalRequestEvidenceSurfaceMemoTest {
    @Test
    fun allThreeSurfacesShareOneContractButNeverShareCachedEvidence() {
        val memo = LocalRequestEvidenceSurfaceMemo()
        var writes = 0L
        fun store() = ++writes
        val key = "session\\u0000model-route"
        assertEquals(1L, memo.ensure(LocalRequestSurfaceKind.MESSAGE, key, "sha-A", 0, ::store))
        assertEquals(1L, memo.ensure(LocalRequestSurfaceKind.MESSAGE, key, "sha-A", 1, ::store))
        assertEquals(2L, memo.ensure(LocalRequestSurfaceKind.TOOL, key, "sha-A", 1, ::store))
        assertEquals(3L, memo.ensure(LocalRequestSurfaceKind.CONTEXT, key, "sha-A", 2, ::store))
        assertEquals(4L, memo.ensure(LocalRequestSurfaceKind.MESSAGE, key, "sha-B", 3, ::store))
        assertEquals(5L, memo.ensure(LocalRequestSurfaceKind.MESSAGE, key, "sha-B", 0, ::store))
        assertEquals(5L, writes)
    }

    @Test
    fun failedPersistenceIsNeverMemoizedAsCommittedEvidence() {
        val memo = LocalRequestEvidenceSurfaceMemo()
        var attempts = 0
        val first = runCatching {
            memo.ensure(LocalRequestSurfaceKind.TOOL, "session", "sha", 0) {
                attempts++
                throw IllegalStateException("disk write failed")
            }
        }
        assertEquals(true, first.isFailure)
        assertEquals(9L, memo.ensure(LocalRequestSurfaceKind.TOOL, "session", "sha", 0) {
            attempts++
            9L
        })
        assertEquals(2, attempts)
        assertEquals(9L, memo.ensure(LocalRequestSurfaceKind.TOOL, "session", "sha", 9) {
            error("duplicated durable evidence")
        })
    }
}
