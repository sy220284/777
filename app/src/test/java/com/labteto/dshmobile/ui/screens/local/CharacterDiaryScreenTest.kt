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
