package com.labteto.dshmobile.local

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.runtime.LocalExecutionService
import com.labteto.dshmobile.local.runtime.LocalExecutionTurnLedger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTurnForegroundLeaseAndroidTest {
    @Test
    fun rejectedForegroundStartDoesNotExecuteProtectedTurn() = runBlocking {
        val rejected = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName {
                throw IllegalStateException("foreground start denied")
            }
        }
        var executed = false
        var rejectedStatus = false
        val admitted = LocalExecutionService.withTurn(
            rejected, "session", { null },
            onRejected = { rejectedStatus = true },
        ) { executed = true }
        assertFalse(admitted)
        assertTrue(rejectedStatus)
        assertFalse(executed)
    }

    @Test
    fun independentTurnsUseDistinctKeysAndReleaseWithoutStealingTheOtherTurn() {
        val ledger = LocalExecutionTurnLedger()
        val first = LocalExecutionTurnLedger.Hold("turn:session:first", "session", "first", 0)
        val second = LocalExecutionTurnLedger.Hold("turn:session:second", "session", "second", 0)
        assertNotEquals(first.key, second.key)
        ledger.acquire(first)
        ledger.acquire(second)
        assertEquals(2, ledger.size())
        assertTrue(ledger.update(second.key, 7))
        assertEquals(7, ledger.latest()?.step)
        assertEquals(second.key, ledger.release(second.key)?.key)
        assertTrue(ledger.contains(first.key))
        assertEquals(first.key, ledger.release(first.key)?.key)
        assertTrue(ledger.isEmpty())
    }

    @Test
    fun updatingAStepDoesNotCreateAnAdditionalForegroundLease() {
        val ledger = LocalExecutionTurnLedger()
        val first = LocalExecutionTurnLedger.Hold("turn:session", "session", "Work", 0)
        ledger.acquire(first)
        for (step in 1..64) assertTrue(ledger.update(first.key, step))
        assertEquals(1, ledger.size())
        assertEquals(64, ledger.latest()?.step)
        assertFalse(ledger.update("unknown", 65))
        assertEquals(first.key, ledger.release(first.key)?.key)
        assertTrue(ledger.isEmpty())
    }
}
