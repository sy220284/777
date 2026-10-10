package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long chat can still recall its own early, ordinary-weight memories on a specific topic. */
class ChatDiaryLongHistoryRecallTest {
    private fun note(id: String, subject: String, event: String, at: Long) = ChatDiaryEntry(
        id = id,
        subjectKey = subject,
        personaName = "叶澜",
        event = event,
        feeling = "我记得当时说过这件事",
        importance = 3,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun oldRelevantDiarySurvivesRecentCandidateWindowInBothChatModes() {
        val old = note("old", "gallery:a", "很久之前我们在湖边修好了蓝色风筝", 1L)
        val anotherPerson = note("other", "gallery:b", "湖边修好了蓝色风筝", 2L)
        val unrelatedNewer = (1..340).map { index ->
            note(
                "new-$index", "gallery:a",
                "第${index}天在车站整理杂志和钥匙", index.toLong() + 10L,
            )
        }
        val entries = listOf(old, anotherPerson) + unrelatedNewer
        val direct = ChatDiaryRecallEngine.search(
            entries, "还记得湖边修蓝色风筝吗", "gallery:a",
            groupAudience = false, maxItems = 4, now = 400L,
        )
        val group = ChatDiaryRecallEngine.search(
            entries, "还记得湖边修蓝色风筝吗", "gallery:a",
            groupAudience = true, maxItems = 4, now = 400L,
        )
        assertTrue(direct.any { it.id == old.id })
        assertEquals(direct.map(ChatDiaryEntry::id), group.map(ChatDiaryEntry::id))
        assertFalse(group.any { it.subjectKey == "gallery:b" })

        val unrelatedQuery = ChatDiaryRecallEngine.search(
            entries, "北极航海极光", "gallery:a",
            groupAudience = true, maxItems = 4, now = 400L,
        )
        assertFalse(unrelatedQuery.any { it.id == old.id })
    }
}
