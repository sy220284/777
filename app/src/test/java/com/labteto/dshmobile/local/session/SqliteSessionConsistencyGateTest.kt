package com.labteto.dshmobile.local.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteSessionConsistencyGateTest {
    @Test fun exportLeaseFencesNewMutationsUntilBothFilesAreCopied() {
        val exclusive = SqliteSessionConsistencyGate.acquireExclusive()
        val attempted = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val thread = Thread {
            attempted.countDown()
            val lease = SqliteSessionConsistencyGate.acquireMutation()
            try { entered.countDown() } finally { lease.unlock() }
        }
        try {
            thread.start()
            assertTrue(attempted.await(3, TimeUnit.SECONDS))
            assertFalse(entered.await(200, TimeUnit.MILLISECONDS))
        } finally {
            exclusive.unlock()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        thread.join(5000)
    }
}
