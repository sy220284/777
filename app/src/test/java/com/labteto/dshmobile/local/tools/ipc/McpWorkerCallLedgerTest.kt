package com.labteto.dshmobile.local.tools.ipc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No late worker RPC may turn a revoked transport into a successful tool result. */
class McpWorkerCallLedgerTest {
    @Test fun hardConcurrentBudgetRejectsBeforeANewCallEntersRemoteExecutor() {
        val ledger = McpWorkerCallLedger(maxInFlight = 2)
        val first = requireNotNull(ledger.begin())
        val second = requireNotNull(ledger.begin())
        assertNull(ledger.begin())
        assertEquals(2, ledger.pendingCount())
        assertTrue(ledger.complete(first))
        assertFalse(ledger.complete(first))
        assertNotNull(ledger.begin())
        assertTrue(ledger.complete(second))
    }

    @Test fun transportRevocationInvalidatesEveryOutstandingTicketAndNeverReopens() {
        val ledger = McpWorkerCallLedger()
        val first = requireNotNull(ledger.begin())
        val second = requireNotNull(ledger.begin())
        assertEquals(2, ledger.revoke())
        assertEquals(0, ledger.revoke())
        assertNull(ledger.begin())
        assertFalse(ledger.complete(first))
        assertFalse(ledger.complete(second))
        assertEquals(0, ledger.pendingCount())
    }

    @Test fun networkReplyAfterDisconnectCannotPublishSuccessEvenIfItWasAlreadyExecuting() {
        val ledger = McpWorkerCallLedger()
        val requestStarted = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var published = true
        val worker = Thread {
            val ticket = requireNotNull(ledger.begin())
            requestStarted.countDown()
            if (disconnected.await(3, TimeUnit.SECONDS)) {
                // Stand-in for late network reply after CLOSE or Binder death.
                published = ledger.complete(ticket)
            }
            completed.countDown()
        }
        worker.start()
        assertTrue(requestStarted.await(3, TimeUnit.SECONDS))
        ledger.revoke()
        disconnected.countDown()
        assertTrue(completed.await(3, TimeUnit.SECONDS))
        assertFalse(published)
        worker.join(1_000)
    }

    @Test fun aNewUserApprovedConnectionMustUseANewLedgerIncarnation() {
        val old = McpWorkerCallLedger()
        val oldTicket = requireNotNull(old.begin())
        old.revoke()
        val next = McpWorkerCallLedger()
        assertFalse(old.complete(oldTicket))
        assertTrue(next.complete(requireNotNull(next.begin())))
    }
}
