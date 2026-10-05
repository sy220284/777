package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.send.LocalSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkExecutionCoordinatorTest {
    @Test
    fun emptyInputIsRejectedInsideWorkFeatureWithoutCallingTurnBridge() {
        val fake = RecordingTurnPort()
        val coordinator = LocalWorkExecutionCoordinator(LocalWorkRunRegistry(LocalRuntimeStateStore()), fake)

        val result = coordinator.send("   ", emptyList())

        assertEquals(LocalSendResult.Empty, result)
        assertEquals(0, fake.sendCalls)
    }

    @Test
    fun preparedWorkInputIsDelegatedToTheNarrowTurnBridge() {
        val fake = RecordingTurnPort()
        val coordinator = LocalWorkExecutionCoordinator(LocalWorkRunRegistry(LocalRuntimeStateStore()), fake)

        val result = coordinator.send("  完成这项任务  ", emptyList())

        assertEquals(LocalSendResult.Started, result)
        assertEquals(1, fake.sendCalls)
        assertEquals("完成这项任务", fake.lastPrepared?.content)
        assertEquals("完成这项任务", fake.lastPrepared?.memoryInput)
    }

    @Test
    fun regenerateDelegatesWithoutRestoringAnEngineProductEntry() {
        val fake = RecordingTurnPort(regenerateResult = true)
        val coordinator = LocalWorkExecutionCoordinator(LocalWorkRunRegistry(LocalRuntimeStateStore()), fake)

        assertTrue(coordinator.regenerateReply("assistant-1"))
        assertEquals("assistant-1", fake.lastRegenerateId)

        fake.regenerateResult = false
        assertFalse(coordinator.regenerateReply("assistant-2"))
    }

    private class RecordingTurnPort(
        var regenerateResult: Boolean = false,
    ) : LocalWorkTurnPort {
        var sendCalls: Int = 0
        var lastPrepared: LocalPreparedSend? = null
        var lastRegenerateId: String? = null

        override fun sendPrepared(prepared: LocalPreparedSend): LocalSendResult {
            sendCalls += 1
            lastPrepared = prepared
            return LocalSendResult.Started
        }

        override fun regenerateReply(messageId: String): Boolean {
            lastRegenerateId = messageId
            return regenerateResult
        }
    }
}
