package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.estimateModelTokens
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContinuityExperienceTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun entry(id: String, experience: String = "story-a", event: String = "那晚等了很久，对方没有赴约") =
        ChatDiaryEntry(id = id, subjectKey = "gallery:a", personaName = "阿青", event = event,
            experienceId = experience, createdAt = 1, updatedAt = 1,
            sources = listOf(ChatDiarySourceRef("session-a", "user-a", "reply-a")))

    @Test fun independentStoriesDoNotRecallOrSupersedeEachOther() {
        val old = entry("old")
        val other = entry("other", "story-b", "海边的约定取消了")
        val results = ChatDiaryRecallEngine.search(listOf(old, other), "你还记得以前吗", "gallery:a", false, 6,
            experienceId = "story-b", sourceSessionIds = setOf("session-b"))
        assertEquals(listOf("other"), results.map(ChatDiaryEntry::id))
        assertFalse(ChatDiarySupersessionPolicy.supersedes(old, other))
    }

    @Test fun oldDiaryNeedsRealSourceFromSelectedStory() {
        val legacy = entry("legacy", "")
        assertTrue(diaryMatchesExperience(legacy, "story-a", setOf("session-a")))
        assertFalse(diaryMatchesExperience(legacy, "fresh-story", setOf("new-session")))
        assertFalse(diaryMatchesExperience(legacy.copy(sources = emptyList()), "story-a", setOf("session-a")))
    }

    @Test fun continuingAnExperienceKeepsIdentityAndAddsSessionSources() {
        val original = ChatContextState().withExperience("story-a", listOf("session-a"))
        val continued = original.withExperience("next-session", listOf("next-session"))
        assertEquals("story-a", continued.experienceId)
        assertEquals(listOf("session-a", "next-session"), continued.experienceSessionIds)
        assertEquals(continued, json.decodeFromString(ChatContextState.serializer(),
            json.encodeToString(ChatContextState.serializer(), continued)))
    }

    @Test fun selectedStoryCanRecallPreviousSessionLineageWithoutOpeningOtherStories() {
        val root = Files.createTempDirectory("story-lineage").toFile()
        try {
            val memory = com.labteto.dshmobile.local.memory.MemoryStore(root, json)
            val old = memory.remember("明晚九点一起去海边", com.labteto.dshmobile.local.memory.MemoryScope.LINEAGE,
                lineageId = "session-a", subjectKey = "gallery:a/experience:story-a")
            memory.remember("明晚九点一起去海边", com.labteto.dshmobile.local.memory.MemoryScope.LINEAGE,
                lineageId = "session-b", subjectKey = "gallery:a/experience:story-b")
            val recalled = memory.search("海边", setOf(com.labteto.dshmobile.local.memory.MemoryScope.LINEAGE),
                null, "new-session", additionalLineageIds = setOf("session-a"),
                recordFilter = { relationshipMemoryMatchesSubject(it, "gallery:a", "new-session", "阿青", "story-a") })
            assertEquals(listOf(old.id), recalled.map { it.id })
            assertTrue(memory.search("海边", setOf(com.labteto.dshmobile.local.memory.MemoryScope.LINEAGE),
                null, "new-session").isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun ordinaryGroupReplyContinuesActualSpeakerAndExplicitAddressCanSwitch() {
        val a = LocalGroupChatMember("a", "a", "阿青")
        val b = LocalGroupChatMember("b", "b", "小岚")
        assertEquals(listOf(a), groupChatResponders("后来呢？", listOf(b, a), "a"))
        assertEquals(listOf(b), groupChatResponders("@小岚 你怎么看？", listOf(a, b), "a"))
        assertEquals(2, groupChatResponders("大家一起聊聊", listOf(a, b), "a").size)
    }

    @Test fun newGroupDoesNotInheritRecentStoryButExistingMemberKeepsGroupState() {
        val root = Files.createTempDirectory("group-experience").toFile()
        try {
            val store = ChatPersonaStore(java.io.File(root, "personas.json"), json)
            val card = PersonaGalleryEntry("a", PersonaProfile(name = "阿青"),
                groupChatState = ChatCharacterState(mood = "旧群争执", updatedAt = 1),
                stories = listOf(PersonaGalleryStory("old", chatState = ChatCharacterState(mood = "旧故事冷战", updatedAt = 2))))
            val fresh = resolveLocalGroupChatMembers(listOf(card), emptyList(), store).single()
            assertEquals("自然", fresh.chatState.mood)
            val previous = fresh.copy(chatState = ChatCharacterState(mood = "这个群的期待"))
            assertEquals("这个群的期待", resolveLocalGroupChatMembers(listOf(card), listOf(previous), store).single().chatState.mood)
        } finally { root.deleteRecursively() }
    }

    @Test fun compressionRetainsActualPromiseAlongsideRequestAndLaterChange() {
        fun message(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
        val history = listOf(message("system", "人设"), message("user", "明晚陪我去海边好吗？"),
            message("assistant", "好，明晚八点我陪你去。"), message("user", "改成九点吧"),
            message("assistant", "那就九点。")) + (1..14).flatMap {
            listOf(message("user", "话题$it"), message("assistant", "回应$it"))
        }
        val compacted = boundedChatRequestHistory(history, recentMessages = 20, compactionBatch = 8).joinToString()
        assertTrue(compacted.contains("明晚八点我陪你去"))
        assertTrue(compacted.contains("那就九点"))
    }

    @Test fun eventOnlyMemoryDoesNotRequireInventedFeelings() {
        val root = Files.createTempDirectory("event-only").toFile()
        try {
            val store = ChatDiaryStore(root, json)
            val saved = store.record(request("我答应明晚九点陪用户去海边"))
            assertNotNull(saved)
            assertEquals("", saved!!.innerThought)
            assertEquals("", saved.feeling)
        } finally { root.deleteRecursively() }
    }

    @Test fun semanticAnchorsRecallDifferentSurfaceWordingWithoutChangingEvent() {
        val memory = entry("waiting").copy(recallAnchors = listOf("突然消失", "害怕被抛下"))
        val results = ChatDiaryRecallEngine.search(listOf(memory), "我怕你突然消失", "gallery:a", false, 4,
            experienceId = "story-a")
        assertEquals(listOf(memory), results)
        assertEquals("那晚等了很久，对方没有赴约", results.single().event)
        assertTrue(shouldSearchDiary("失约"))
    }

    @Test fun userCorrectionWithdrawsDependentMisunderstandingButKeepsUnrelatedState() {
        val source = entry("wrong").copy(feeling = "被故意忽视", unresolvedEcho = "用户还欠我解释")
        val correction = ChatDiaryRevision("那晚手机没电，后来已经说明情况", sources = source.sources,
            updatedAt = 2, userCorrected = true)
        val corrected = source.copy(event = correction.event, revisions = ChatDiaryEntryPolicy.revisionsOf(source) + correction)
        val state = ChatCharacterState(mood = "委屈", activeGoal = "准备自己的演出", currentUserImpression = "他故意失约",
            dynamics = RelationshipDynamics(unresolvedConflict = "用户还欠我解释"),
            stateObservations = listOf(ChatStateObservation(field = "currentUserImpression", value = "他故意失约",
                sourceUserMessageId = "user-a", sourceAssistantMessageId = "reply-a"),
                ChatStateObservation(field = "mood", value = "委屈", interpretation = false, sourceUserMessageId = "user-a")))
        val reconciled = reconcileDiaryState(state, listOf(corrected))
        assertEquals("", reconciled.currentUserImpression)
        assertEquals("", reconciled.dynamics.unresolvedConflict)
        assertEquals("准备自己的演出", reconciled.activeGoal)
        assertEquals("委屈", reconciled.mood)
        assertEquals(reconciled, reconcileDiaryState(reconciled, listOf(corrected)))
    }

    @Test fun correctionSurvivesStoreReloadAndLateBackgroundCannotRecreateIt() {
        val root = Files.createTempDirectory("diary-correction").toFile()
        try {
            val store = ChatDiaryStore(root, json)
            val saved = store.record(request("我答应明晚九点陪用户去海边"))!!
            assertTrue(store.correctEntry(saved.subjectKey, saved.id, saved.updatedAt,
                ChatDiaryDelta(event = "约定已经取消，双方已经说明情况")))
            val restored = ChatDiaryStore(root, json)
            assertEquals(null, restored.record(request("我答应明晚九点陪用户去海边")))
            val state = ChatCharacterState(unresolvedThreads = listOf("明晚去海边"),
                stateObservations = listOf(ChatStateObservation(field = "unresolvedThreads", value = "明晚去海边",
                    sourceUserMessageId = "user-a")))
            assertTrue(restored.reconcileState("gallery:a", state,
                ChatContextState().withExperience("story-a", listOf("session-a"))).unresolvedThreads.isEmpty())
            // A genuinely new event from another turn remains writable.
            assertNotNull(restored.record(request("我答应下周陪用户去海边").copy(sourceUserMessageIds = listOf("user-new"),
                sourceAssistantMessageIds = listOf("reply-new"))))
        } finally { root.deleteRecursively() }
    }

    @Test fun authoredFactsArePackedWholeAndUnusedBudgetCanFitOtherCategories() {
        val fact = "经历过背叛，因此对承诺格外在意，但仍想尝试信任。"
        val heading = "【人物】阿青"
        val result = packCharacterSections(heading, listOf("经历" to fact, "语言" to "说话简短。"),
            estimateModelTokens(heading) + estimateModelTokens(fact) + estimateModelTokens("\n\n【经历】\n") + 2)
        assertTrue(result.contains(fact))
        assertFalse(result.contains("【语言】"))
        assertTrue(estimateModelTokens(result) <= estimateModelTokens(heading) + estimateModelTokens(fact) + estimateModelTokens("\n\n【经历】\n") + 2)
        assertEquals(listOf("短而完整的事实"), packCompleteCharacterUnits(listOf("大段".repeat(1000), "短而完整的事实"), 30))
    }

    @Test fun unquotedPsychologyRemainsMarkedAsInference() {
        val memory = entry("uncertain").copy(innerThought = "我也许有些不安")
        assertTrue(renderRecalledChatDiary(memory, false).contains("尚未确认"))
        assertFalse(renderRecalledChatDiary(memory.copy(subjectiveInferred = false, subjectiveEvidence = "我有点不安"), false)
            .contains("尚未确认"))
    }

    private fun request(event: String) = ChatDiaryWriteRequest(
        subjectKey = "gallery:a", personaName = "阿青", delta = ChatDiaryDelta(event = event, importance = 4),
        turnSignificance = "MAJOR", sourceMode = ChatDiarySourceMode.DIRECT, sourceSessionId = "session-a",
        sourceUserMessageIds = listOf("user-a"), sourceAssistantMessageIds = listOf("reply-a"),
        evidenceText = event, generation = 1, experienceId = "story-a", experienceSessionIds = listOf("session-a"),
    )
}
