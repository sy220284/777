package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPersonaGalleryTest {
    @Test
    fun archivesFullHistoryButOnlyRecentDialogueEntersNewChat() {
        val story = PersonaGalleryStory(
            id = "story-1",
            title = "雪夜",
            notes = "雪夜在桥边重逢",
            history = (1..30).map { index ->
                LocalHarnessMessage(
                    "$index",
                    if (index % 2 == 0) "assistant" else "user",
                    "对白$index",
                    createdAt = index.toLong(),
                )
            },
        )
        val entry = PersonaGalleryEntry(
            id = "gallery-1",
            persona = PersonaProfile(name = "阿青"),
            stories = listOf(story),
        )

        val context = entry.storyContext("story-1")
        assertTrue(context.contains("雪夜在桥边重逢"))
        assertTrue(context.contains("对白30"))
        assertFalse(context.contains("对白1\n"))
        assertEquals(30, story.history.size)
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
    fun sameNameDifferentWorldSettingsStaySeparate() {
        val teyvat = PersonaProfile(name = "神里绫华", worldSetting = "提瓦特稻妻")
        val modern = PersonaProfile(name = "神里绫华", worldSetting = "现代东京校园")

        assertFalse(samePersonaIdentity(teyvat, modern))
    }

    @Test
    fun repeatedSaveDoesNotDuplicateArchivedMessagesInsideOneStory() {
        val base = PersonaGalleryStory(
            id = "story-1",
            history = listOf(
                LocalHarnessMessage("m1", "user", "你来了", createdAt = 1L),
                LocalHarnessMessage("m2", "assistant", "嗯。", createdAt = 2L),
            ),
            updatedAt = 2L,
        )
        val incoming = PersonaGalleryStory(
            id = "story-1",
            history = listOf(
                LocalHarnessMessage("m1", "user", "你来了", createdAt = 1L),
                LocalHarnessMessage("m2", "assistant", "嗯。", createdAt = 2L),
                LocalHarnessMessage("m3", "user", "一起走吧", createdAt = 3L),
            ),
            updatedAt = 3L,
        )

        val merged = mergeGalleryStories(base, incoming)

        assertEquals(listOf("m1", "m2", "m3"), merged.history.map { it.id })
    }

    @Test
    fun legacySameDialogueWithDifferentIdsIsStillDeduplicated() {
        val base = PersonaGalleryStory(
            id = "story-1",
            history = listOf(
                LocalHarnessMessage("old-id", "user", "同一句对白", createdAt = 10L),
            ),
        )
        val incoming = PersonaGalleryStory(
            id = "story-1",
            history = listOf(
                LocalHarnessMessage("new-id", "user", "同一句对白", createdAt = 10L),
            ),
        )

        val merged = mergeGalleryStories(base, incoming)

        assertEquals(1, merged.history.size)
    }

    @Test
    fun legacyDuplicateCharacterCardsCompactButKeepStoriesSeparate() {
        val older = PersonaGalleryEntry(
            id = "gallery-old",
            persona = PersonaProfile(
                id = "gallery-old",
                name = "神里绫华",
                identity = "社奉行神里家大小姐",
            ),
            history = listOf(LocalHarnessMessage("m1", "user", "早上好", createdAt = 1L)),
            sourceSessionId = "session-old",
            updatedAt = 10L,
        )
        val newer = PersonaGalleryEntry(
            id = "gallery-new",
            persona = PersonaProfile(
                id = "gallery-new",
                name = "神里绫华",
                personality = "温柔克制",
            ),
            history = listOf(LocalHarnessMessage("m2", "assistant", "早上好。", createdAt = 2L)),
            sourceSessionId = "session-new",
            updatedAt = 20L,
        )

        val compacted = compactLegacyDuplicateGalleryEntries(listOf(older, newer))

        assertEquals(1, compacted.size)
        assertEquals("gallery-new", compacted.single().id)
        assertEquals("社奉行神里家大小姐", compacted.single().persona.identity)
        assertEquals("温柔克制", compacted.single().persona.personality)
        assertEquals(2, compacted.single().stories.size)
        assertEquals(setOf("session-old", "session-new"), compacted.single().stories.flatMap { it.sourceSessionIds }.toSet())
    }

    @Test
    fun archivedDialogueDeletionCreatesTombstoneSoLaterSaveCannotRestoreIt() {
        val first = LocalHarnessMessage("m1", "user", "第一句", createdAt = 1L)
        val second = LocalHarnessMessage("m2", "assistant", "第二句", createdAt = 2L)
        val story = PersonaGalleryStory(id = "story-1", history = listOf(first, second))

        val deleted = removeArchivedGalleryMessage(story, galleryMessageArchiveKey(first))!!
        val resaved = mergeGalleryStories(
            deleted,
            PersonaGalleryStory(id = "story-1", history = listOf(first, second)),
        )

        assertEquals(listOf("m2"), resaved.history.map { it.id })
        assertTrue(galleryMessageArchiveKey(first) in resaved.excludedMessageKeys)
    }

    @Test
    fun savedStoryBecomesDirtyWhenNewDialogueArrives() {
        val saved = LocalHarnessMessage("m1", "user", "早上好", createdAt = 1L)
        val fresh = LocalHarnessMessage("m2", "assistant", "早上好。", createdAt = 2L)
        val entry = PersonaGalleryEntry(
            id = "gallery-1",
            persona = PersonaProfile(id = "gallery-1", name = "阿青", identity = "剑客"),
            stories = listOf(PersonaGalleryStory(id = "story-1", history = listOf(saved))),
        )

        assertFalse(
            galleryEntryHasUnsavedChanges(
                entry,
                "story-1",
                entry.persona,
                listOf(saved),
                ChatCharacterState(),
            ),
        )
        assertTrue(
            galleryEntryHasUnsavedChanges(
                entry,
                "story-1",
                entry.persona,
                listOf(saved, fresh),
                ChatCharacterState(),
            ),
        )
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
