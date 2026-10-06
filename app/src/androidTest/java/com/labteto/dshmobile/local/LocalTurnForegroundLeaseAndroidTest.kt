package com.labteto.dshmobile.local

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.runtime.LocalExecutionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTurnForegroundLeaseAndroidTest {
    @Test
    fun overlappingTurnsOfTheSameSessionReleaseOnlyTheirOwnHold() = runBlocking {
        val context = RecordingContext()
        LocalExecutionService.withTurn(context, "session", { null }) {
            LocalExecutionService.withTurn(context, "session", { null }) { }
        }
        val intents = context.intents
        assertEquals(4, intents.size)
        assertNotEquals(intents[0].getStringExtra("key"), intents[1].getStringExtra("key"))
        assertEquals(intents[1].getStringExtra("key"), intents[2].getStringExtra("key"))
        assertEquals(intents[0].getStringExtra("key"), intents[3].getStringExtra("key"))
        assertTrue(intents.all { it.getStringExtra("session_id") == "session" })
        assertEquals(LocalExecutionService.OUTCOME_COMPLETED, intents[3].getStringExtra("outcome"))
    }

    @Test
    fun cancellationAndFailureReleaseTheHoldWithTheCorrectOutcome() = runBlocking {
        for (cancelled in listOf(false, true)) {
            val context = RecordingContext()
            val error = runCatching {
                LocalExecutionService.withTurn(context, "session", { null }) {
                    if (cancelled) throw CancellationException("stop") else throw IllegalStateException("failed")
                }
            }.exceptionOrNull()
            assertTrue(error != null)
            assertEquals(2, context.intents.size)
            assertEquals(context.intents[0].getStringExtra("key"), context.intents[1].getStringExtra("key"))
            assertEquals(if (cancelled) LocalExecutionService.OUTCOME_CANCELLED else LocalExecutionService.OUTCOME_FAILED,
                context.intents[1].getStringExtra("outcome"))
        }
    }

    private class RecordingContext : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        val intents = mutableListOf<Intent>()
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName {
            intents += Intent(service)
            return ComponentName(this, LocalExecutionService::class.java)
        }
    }
}
