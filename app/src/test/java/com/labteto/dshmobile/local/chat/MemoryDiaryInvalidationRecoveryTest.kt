package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemorySourceRef
import com.labteto.dshmobile.local.memory.MemoryStore
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryDiaryInvalidationRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun memory() = MemoryStore(File(temporary.root, "memory"), Json)
    private fun diary() = ChatDiaryStore(File(temporary.root, "diary"), Json)

    @Test fun interruptedCorrectionRecoversFromDurableOutboxWithoutErasingUnrelatedSources() {
        verifyInterruptedRecovery(disable = false)
    }

    @Test fun interruptedDisableRecoversEvenThoughTheFactIsNoLongerActive() {
        verifyInterruptedRecovery(disable = true)
    }

    private fun verifyInterruptedRecovery(disable: Boolean) {
        val store = memory()
        val projection = diary()
        val event = "用户答应周末和我去海边看日落"
        val source = store.remember(event, MemoryScope.GLOBAL,
            sourceSessionId = "s", sourceMessageId = "u1")
        val old = projection.record(request(event, "u1"))!!
        val unrelated = projection.record(request("用户记得下周要去图书馆看书", "u2"))!!
        if (disable) store.forget(source.id) else store.update(source.id, content = "周末改去山上")
        assertThrows(IOException::class.java) {
            recoverMemoryDiaryInvalidations(store) { throw IOException("日记写入中断") }
        }
        assertTrue(diary().listActive("gallery:a").any { it.id == old.id })
        // Also cover death after diary commit but before outbox acknowledgement.
        assertThrows(IOException::class.java) {
            recoverMemoryDiaryInvalidations(memory()) { sources ->
                invalidate(diary(), sources)
                throw IOException("清理已落盘，确认前进程中断")
            }
        }
        assertTrue(memory().pendingSourceInvalidations().isNotEmpty())
        recoverMemoryDiaryInvalidations(memory()) { invalidate(diary(), it) }
        assertTrue(memory().pendingSourceInvalidations().isEmpty())
        assertEquals(listOf(unrelated.id), diary().listActive("gallery:a").map { it.id })
        // Late replay of the discarded generated fact remains rejected after restart.
        assertNull(diary().record(request(event, "u1")))
        if (disable) assertTrue(memory().listActiveFromMessage("s", "u1").isEmpty())
        else assertEquals("周末改去山上", memory().listActiveFromMessage("s", "u1").single().content)
    }

    private fun invalidate(store: ChatDiaryStore, sources: List<MemorySourceRef>): Int =
        sources.sumOf { store.invalidateGeneratedFromMessage(it.sessionId, it.messageId) }

    private fun request(event: String, source: String) = ChatDiaryWriteRequest(
        subjectKey = "gallery:a", personaName = "阿青",
        delta = ChatDiaryDelta(event = event, feeling = "我记住了", importance = 4),
        turnSignificance = "MAJOR", sourceMode = ChatDiarySourceMode.DIRECT,
        sourceSessionId = "s", sourceUserMessageIds = listOf(source),
        sourceAssistantMessageIds = listOf("a-$source"), evidenceText = event, generation = 1,
    )
}
