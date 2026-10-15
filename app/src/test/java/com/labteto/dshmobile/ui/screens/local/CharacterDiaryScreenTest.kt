package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.chat.ChatDiaryDisclosure
import com.labteto.dshmobile.local.chat.ChatDiaryEntry
import com.labteto.dshmobile.local.chat.ChatDiarySourceMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterDiaryScreenTest {
    @Test
    fun subjectsPreferCurrentGalleryIdentityWithoutDuplicatingIt() {
        val current = PersonaProfile(id = "persona-a", name = "阿青")
        val gallery = listOf(
            PersonaGalleryEntry(id = "a", persona = current),
            PersonaGalleryEntry(id = "b", persona = PersonaProfile(id = "persona-b", name = "阿白")),
        )

        val subjects = characterDiarySubjects(gallery, current, currentGalleryId = "a")

        assertEquals(listOf("gallery:a", "gallery:b"), subjects.map(CharacterDiarySubject::key))
        assertEquals(listOf("阿青", "阿白"), subjects.map(CharacterDiarySubject::name))
    }

    @Test
    fun searchMatchesPsychologyAndRelationshipMeaningNotOnlyEventText() {
        val entries = listOf(
            diary(
                id = "a",
                event = "一起去了海边",
                thought = "我想装得不在意，其实一直期待这次见面",
                meaning = "我们第一次有了真正属于两个人的计划",
            ),
            diary(id = "b", event = "在书店买了书", thought = "很平静"),
        )

        val psychology = filterCharacterDiaryEntries(entries, "装得不在意", CharacterDiaryFilter.ALL)
        val relationship = filterCharacterDiaryEntries(entries, "两个人的计划", CharacterDiaryFilter.ALL)

        assertEquals(listOf("a"), psychology.map(ChatDiaryEntry::id))
        assertEquals(listOf("a"), relationship.map(ChatDiaryEntry::id))
    }

    @Test
    fun sourceAndPrivacyFiltersKeepTheirOwnMeaning() {
        val entries = listOf(
            diary(id = "direct", source = ChatDiarySourceMode.DIRECT),
            diary(id = "group", source = ChatDiarySourceMode.GROUP, disclosure = ChatDiaryDisclosure.PUBLIC),
            diary(id = "secret", source = ChatDiarySourceMode.DIRECT, disclosure = ChatDiaryDisclosure.PRIVATE),
        )

        assertEquals(
            listOf("group"),
            filterCharacterDiaryEntries(entries, "", CharacterDiaryFilter.GROUP).map(ChatDiaryEntry::id),
        )
        assertEquals(
            listOf("secret"),
            filterCharacterDiaryEntries(entries, "", CharacterDiaryFilter.PRIVATE).map(ChatDiaryEntry::id),
        )
        assertTrue(filterCharacterDiaryEntries(entries, "不存在", CharacterDiaryFilter.ALL).isEmpty())
    }


    @Test
    fun dailyReadingGroupsByOriginalDayAndExperienceWithoutMovingCorrectedEntries() {
        fun at(day: Int, hour: Int): Long = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.OCTOBER, day, hour, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val first = diary(id = "first", event = "早上买了一本书")
            .copy(createdAt = at(14, 9), updatedAt = at(15, 12), experienceId = "story-a")
        val second = diary(id = "second", event = "下午遇见老朋友")
            .copy(createdAt = at(14, 16), updatedAt = at(14, 16), experienceId = "story-a")
        val independent = diary(id = "independent", event = "在另一段故事散步")
            .copy(createdAt = at(14, 18), updatedAt = at(14, 18), experienceId = "story-b")
        val nextDay = diary(id = "next", event = "第二天去散步")
            .copy(createdAt = at(15, 8), updatedAt = at(15, 8), experienceId = "story-a")
        val days = groupCharacterDiaryDays(listOf(first, second, independent, nextDay))
        assertEquals(3, days.size)
        assertEquals(listOf("next"), days.first().entries.map(ChatDiaryEntry::id))
        assertEquals(listOf("first", "second"),
            days.single { it.experienceId == "story-a" && it.entries.size == 2 }.entries.map(ChatDiaryEntry::id))
        assertEquals(listOf("independent"),
            days.single { it.experienceId == "story-b" }.entries.map(ChatDiaryEntry::id))
    }

    @Test
    fun diaryReadingUsesPersonalToneAndHidesProductUserLabelWithoutInventingFeelings() {
        val personal = diary(id = "a", event = "用户确认周末和我去海边",
            thought = "我一直偷偷期待那天")
        val line = characterDiaryReadingLine(personal)
        assertTrue(line.contains("对方确认周末和我去海边"))
        assertTrue(line.contains("我一直偷偷期待那天"))
        org.junit.Assert.assertFalse(line.contains("用户"))
        val eventOnly = characterDiaryReadingLine(diary(id = "b", event = "今天我把书买回来了"))
        assertEquals("今天我把书买回来了。", eventOnly)
        org.junit.Assert.assertFalse(eventOnly.contains("我很开心"))
    }

    @Test
    fun dailySummaryRemainsBriefEvenWithManyLongMemories() {
        val entries = (0 until 12).map {
            diary(id = it.toString(), event = "今天我想了很多" + "细节".repeat(75), thought = "我还在琢磨这件事")
                .copy(importance = it % 5)
        }
        assertTrue(characterDiaryDayNarrative(entries).length <= 360)
        assertEquals(12, entries.size)
    }

    private fun diary(
        id: String,
        event: String = "共同经历",
        thought: String = "",
        meaning: String = "",
        source: ChatDiarySourceMode = ChatDiarySourceMode.DIRECT,
        disclosure: ChatDiaryDisclosure = ChatDiaryDisclosure.SHAREABLE,
    ) = ChatDiaryEntry(
        id = id,
        subjectKey = "gallery:a",
        personaName = "阿青",
        event = event,
        innerThought = thought,
        relationshipMeaning = meaning,
        sourceMode = source,
        disclosure = disclosure,
        createdAt = 1L,
        updatedAt = if (id == "a") 2L else 1L,
    )
}
