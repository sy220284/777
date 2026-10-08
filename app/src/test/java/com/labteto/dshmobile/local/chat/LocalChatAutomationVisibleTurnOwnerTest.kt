package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatAutomationVisibleTurnOwnerTest {
    private fun state(id: String = "chat") = LocalHarnessState(
        loading = false, sessionId = id, usageMode = LocalUsageMode.CHAT,
    )

    private fun handle(id: String = "chat") = LocalAgentRunHandle(
        initialSessionId = id, maxPendingInputs = 2,
    )

    @Test
    fun sessionSwitchDuringWaitDetachesWithoutClaimingTheForegroundHandle() = runBlocking {
        val handle = handle()
        val existing = Job()
        val pending = Job()
        handle.job = existing
        var currentId = "chat"
        var visible = state()
        var polls = 0
        var claims = 0
        val claimed = awaitAutomationVisibleTurn(
            tryClaim = {
                tryClaimAutomationVisibleTurn(
                    handle, "chat", currentId, visible, false, pending, onClaimed = { claims++ },
                )
            },
            onBusy = {
                polls++
                currentId = "new"
                visible = state("new")
            },
        )
        assertFalse(claimed)
        assertEquals(1, polls)
        assertEquals(0, claims)
        assertSame(existing, handle.job)
        existing.cancel()
        pending.cancel()
    }

    @Test
    fun transitionModeChangeAndCancellationBlockClaim() {
        val handle = handle()
        val owner = Job()
        assertEquals(
            AutomationVisibleClaim.DETACHED,
            tryClaimAutomationVisibleTurn(handle, "chat", "chat", state(), true, owner),
        )
        assertEquals(
            AutomationVisibleClaim.DETACHED,
            tryClaimAutomationVisibleTurn(
                handle, "chat", "chat", state().copy(usageMode = LocalUsageMode.WORK), false, owner,
            ),
        )
        handle.cancellationRequested = true
        assertEquals(
            AutomationVisibleClaim.DETACHED,
            tryClaimAutomationVisibleTurn(handle, "chat", "chat", state(), false, owner),
        )
        assertNull(handle.job)
        owner.cancel()
    }

    @Test
    fun sameSessionBusyWaitStillClaimsAfterForegroundBecomesIdle() = runBlocking {
        val handle = handle()
        val existing = Job()
        val pending = Job()
        handle.job = existing
        var claims = 0
        val claimed = awaitAutomationVisibleTurn(
            tryClaim = {
                tryClaimAutomationVisibleTurn(
                    handle, "chat", "chat", state(), false, pending, onClaimed = { claims++ },
                )
            },
            onBusy = {
                existing.complete()
                handle.job = null
            },
        )
        assertTrue(claimed)
        assertSame(pending, handle.job)
        assertEquals(1, claims)
        pending.cancel()
    }

    @Test
    fun staleReleaseCannotClearAnotherOwnerOrItsInteractions() {
        val handle = handle()
        val stale = Job()
        val current = Job()
        handle.job = current
        var cleanups = 0
        assertFalse(
            releaseAutomationVisibleTurn(
                handle, "chat", "chat", state(), stale, onReleased = { cleanups++ },
            ),
        )
        assertEquals(0, cleanups)
        assertSame(current, handle.job)
        assertTrue(
            releaseAutomationVisibleTurn(
                handle, "chat", "chat", state(), current, onReleased = { cleanups++ },
            ),
        )
        assertEquals(1, cleanups)
        assertNull(handle.job)
        stale.cancel()
        current.cancel()
    }

    @Test
    fun lateReleaseAfterSessionRebindDoesNotTouchNewSession() {
        val handle = handle()
        val oldOwner = Job()
        handle.job = oldOwner
        oldOwner.complete()
        handle.rebindSession("new")
        val newOwner = Job()
        handle.job = newOwner
        var cleanups = 0
        assertFalse(
            releaseAutomationVisibleTurn(
                handle, "chat", "new", state("new"), oldOwner,
                onReleased = { cleanups++ },
            ),
        )
        assertEquals(0, cleanups)
        assertSame(newOwner, handle.job)
        newOwner.cancel()
    }

    @Test
    fun failedClaimProjectionReleasesClaimedSlot() {
        val handle = handle()
        val owner = Job()
        val failure = IllegalStateException("projection failure")
        val result = runCatching {
            tryClaimAutomationVisibleTurn(
                handle, "chat", "chat", state(), false, owner,
                onClaimed = { throw failure },
            )
        }
        assertSame(failure, result.exceptionOrNull())
        assertNull(handle.job)
        owner.cancel()
    }

    @Test
    fun outerTimeoutAfterClaimReleasesTheExactVisibleOwner() = runBlocking {
        val handle = handle()
        val owner = Job()
        var cleanups = 0
        val timedOut = CancellationException("outer budget cancelled")
        val result = runCatching {
            acquireAutomationVisibleTurnWithinBudget(
                targetSessionId = "chat",
                automationJob = owner,
                budget = LocalChatAutomationTimeoutBudget(5_000L, 5_000L),
                acquireVisibleTurn = { _, claimedJob ->
                    handle.job = claimedJob
                    throw timedOut
                },
                releaseVisibleTurn = { sessionId, releasedJob ->
                    releaseAutomationVisibleTurn(
                        handle, sessionId, "chat", state(), releasedJob,
                        onReleased = { cleanups++ },
                    )
                },
            )
        }
        assertSame(timedOut, result.exceptionOrNull())
        assertNull(handle.job)
        assertEquals(1, cleanups)
        owner.cancel()
    }

    @Test
    fun failedOuterAcquireNeverReleasesAnUnrelatedForegroundOwner() = runBlocking {
        val handle = handle()
        val current = Job()
        val attempted = Job()
        handle.job = current
        var cleanups = 0
        val failure = IllegalStateException("failed preflight")
        val result = runCatching {
            acquireAutomationVisibleTurnWithinBudget(
                targetSessionId = "chat",
                automationJob = attempted,
                budget = LocalChatAutomationTimeoutBudget(5_000L, 5_000L),
                acquireVisibleTurn = { _, _ -> throw failure },
                releaseVisibleTurn = { id, job ->
                    releaseAutomationVisibleTurn(
                        handle, id, "chat", state(), job,
                        onReleased = { cleanups++ },
                    )
                },
            )
        }
        assertSame(failure, result.exceptionOrNull())
        assertSame(current, handle.job)
        assertEquals(0, cleanups)
        current.cancel()
        attempted.cancel()
    }

    @Test
    fun cancelledWaitDoesNotClaimOrReleaseAnotherOwner() = runBlocking {
        val handle = handle()
        val existing = Job()
        val pending = Job()
        handle.job = existing
        val result = runCatching {
            awaitAutomationVisibleTurn(
                tryClaim = { tryClaimAutomationVisibleTurn(handle, "chat", "chat", state(), false, pending) },
                onBusy = { throw CancellationException("cancelled waiting") },
            )
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertSame(existing, handle.job)
        existing.cancel()
        pending.cancel()
    }
}
