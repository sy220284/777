package com.labteto.dshmobile.local.chat

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatDiaryInvalidationTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun store() = ChatDiaryStore(File(tmp.root, "diary"), Json { encodeDefaults = true })

    private fun write(session: String, source: String, note: String): ChatDiaryEntry =
        store().record(ChatDiaryWriteRequest(
            subjectKey = "gallery:a", personaName = "阿白",
            delta = ChatDiaryDelta(event = note, importance = 4),
            turnSignificance = "important", sourceMode = ChatDiarySourceMode.DIRECT,
            sourceSessionId = session, sourceUserMessageIds = listOf(source),
            sourceAssistantMessageIds = listOf("answer-$source"),
            evidenceText = note, generation = 0L,
        ))!!

    @Test fun memoryCorrectionInvalidatesOnlyExactlySourcedGeneratedDiary() {
        val stale = write("chat-a", "u-one", "用户答应周末去海边")
        val unrelated = write("chat-a", "u-two", "用户答应明天读书")
        assertEquals(1, store().invalidateGeneratedFromMessage("chat-a", "u-one"))
        assertEquals(listOf(unrelated.id), store().listActive("gallery:a").map { it.id })
        assertTrue(store().search("海边", "gallery:a", false, 5).none { it.id == stale.id })
        assertEquals(0, store().invalidateGeneratedFromMessage("chat-b", "u-one"))
        assertEquals(0, store().invalidateGeneratedFromMessage("chat-a", "u-one"))
    }


    @Test fun cancelledOldSummaryCannotReappearAfterDelayedAsynchronousConsolidation() {
        val original = write("chat-a", "u-late", "旧记忆：答应坐火车")
        assertEquals(1, store().invalidateGeneratedFromMessage("chat-a", "u-late"))
        assertTrue(store().listActive("gallery:a").isEmpty())
        assertEquals(null, store().record(ChatDiaryWriteRequest(
            subjectKey = "gallery:a", personaName = "阿白",
            delta = ChatDiaryDelta(event = "旧记忆：答应坐火车", importance = 4),
            turnSignificance = "important", sourceMode = ChatDiarySourceMode.DIRECT,
            sourceSessionId = "chat-a", sourceUserMessageIds = listOf("u-late"),
            sourceAssistantMessageIds = listOf("answer-u-late"),
            evidenceText = "旧记忆：答应坐火车", generation = 0L,
        )))
        assertTrue(store().listActive("gallery:a").isEmpty())
        assertTrue(original.id.isNotBlank())
    }

    @Test fun explicitAuthorCorrectionOfDiarySurvivesLaterMemoryInvalidation() {
        val current = write("chat-b", "u-three", "用户会参加晚会")
        assertTrue(store().correctEntry("gallery:a", current.id, current.updatedAt,
            ChatDiaryDelta(event = "用户明确取消了晚会")))
        assertEquals(0, store().invalidateGeneratedFromMessage("chat-b", "u-three"))
        assertEquals("用户明确取消了晚会", store().listActive("gallery:a").single().event)
    }
}
