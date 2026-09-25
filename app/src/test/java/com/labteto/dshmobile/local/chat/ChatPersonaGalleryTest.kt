package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPersonaGalleryTest {
    @Test
    fun archivesFullHistoryButOnlyRecentDialogueEntersNewChat() {
        val entry = PersonaGalleryEntry(
            id = "gallery-1",
            persona = PersonaProfile(name = "阿青"),
            storyNotes = "雪夜在桥边重逢",
            history = (1..30).map { index ->
                LocalHarnessMessage(
                    "$index",
                    if (index % 2 == 0) "assistant" else "user",
                    "对白$index",
                    createdAt = index.toLong(),
                )
            },
        )

        val context = entry.storyContext()
        assertTrue(context.contains("雪夜在桥边重逢"))
        assertTrue(context.contains("对白30"))
        assertFalse(context.contains("对白1\n"))
        assertEquals(30, entry.history.size)
    }

    @Test
    fun sameCharacterMergesIntoOneRicherProfileWithoutRepeatingShortVersion() {
        val base = PersonaProfile(
            id = "gallery-1",
            name = "小岚",
            identity = "花店店主",
            personality = "嘴硬心软",
            hardConstraints = listOf("不会无故失约"),
        )
        val incoming = PersonaProfile(
            id = "temp",
            name = "小岚",
            identity = "经营街角花店的店主",
            personality = "嘴硬心软；遇到重要的人会主动解释",
            hardConstraints = listOf("不会无故失约", "不拿感情问题开恶意玩笑"),
        )

        assertTrue(samePersonaIdentity(base, incoming))
        val merged = mergePersonaProfiles(base, incoming)

        assertEquals("经营街角花店的店主", merged.identity)
        assertEquals("嘴硬心软；遇到重要的人会主动解释", merged.personality)
        assertEquals(
            listOf("不会无故失约", "不拿感情问题开恶意玩笑"),
            merged.hardConstraints,
        )
    }

    @Test
    fun repeatedSaveDoesNotDuplicateArchivedMessages() {
        val base = PersonaGalleryEntry(
            id = "gallery-1",
            persona = PersonaProfile(id = "gallery-1", name = "阿青", identity = "剑客"),
            history = listOf(
                LocalHarnessMessage("m1", "user", "你来了", createdAt = 1L),
                LocalHarnessMessage("m2", "assistant", "嗯。", createdAt = 2L),
            ),
            updatedAt = 2L,
        )
        val incoming = PersonaGalleryEntry(
            id = "gallery-1",
            persona = PersonaProfile(id = "gallery-1", name = "阿青", identity = "剑客"),
            history = listOf(
                LocalHarnessMessage("m1", "user", "你来了", createdAt = 1L),
                LocalHarnessMessage("m2", "assistant", "嗯。", createdAt = 2L),
                LocalHarnessMessage("m3", "user", "一起走吧", createdAt = 3L),
            ),
            updatedAt = 3L,
        )

        val merged = mergeGalleryEntries(base, incoming)

        assertEquals(listOf("m1", "m2", "m3"), merged.history.map { it.id })
    }

    @Test
    fun selectedInspectionSuggestionAppendsOnceAndKeepsExistingPersona() {
        val profile = PersonaProfile(
            name = "小岚",
            relationship = "和用户是多年好友",
            signaturePhrases = listOf("少来"),
        )
        val suggestion = PersonaAppendSuggestion(
            field = "relationship",
            value = "私下会叫用户小名",
            evidence = "对话里多次使用小名",
        )
        val phrase = PersonaAppendSuggestion(
            field = "signaturePhrases",
            value = "少来",
            evidence = "已有内容，不应重复",
        )

        val merged = applyPersonaSuggestions(profile, listOf(suggestion, phrase, phrase))

        assertEquals("和用户是多年好友；私下会叫用户小名", merged.relationship)
        assertEquals(listOf("少来"), merged.signaturePhrases)
    }

    @Test
    fun placeholderPersonaDoesNotTriggerNewCharacterPrompt() {
        assertFalse(isMeaningfulGalleryPersona(PersonaProfile()))
        assertTrue(
            isMeaningfulGalleryPersona(
                PersonaProfile(name = "小岚", identity = "花店店主"),
            ),
        )
    }
}
