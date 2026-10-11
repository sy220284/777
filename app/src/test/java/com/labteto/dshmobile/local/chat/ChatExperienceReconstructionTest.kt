package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.estimateModelTokens
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatExperienceReconstructionTest {
    @Test fun everydayConversationRetainsAllSixteenAuthoredFactCategories() {
        val persona = PersonaProfile(name = "阿青", coreIdentity = "山城里的摄影师", facts =
            CharacterFactCategories.canonical.mapIndexed { index, category ->
                CharacterFact("fact-$index", category, "独立经历编号$index", provenance = CharacterFactProvenance.USER_CREATED)
            })
        val context = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
            .project(persona, ChatCharacterState(), ChatContextState(), "嗯", null)
        CharacterFactCategories.canonical.indices.forEach { index ->
            assertTrue(context.stablePrompt.contains("独立经历编号$index"))
        }
        assertFalse(context.dynamicPrompt.contains("本轮模式"))
    }

    @Test fun shortCardSectionsReleaseTheirUnusedBudgetToLongerExperiences() {
        val sections = listOf("身份", "经历".repeat(100), "语言".repeat(50))
        val budget = allocateCharacterContextBudget(sections, 100)
        assertEquals(100, budget.sum())
        assertEquals(estimateModelTokens(sections.first()), budget.first())
        assertTrue(budget[1] > 33)
        assertEquals(listOf(0, 0, 0), allocateCharacterContextBudget(sections, 0))
    }

    @Test fun aShortContinuationRecallsTheOngoingPublicExperience() {
        val context = ChatContextState(pendingTurns = listOf(ChatPendingTurn(
            sequence = 3L, userMessage = "那次我没来，是因为临时接到医院的电话。",
            assistantMessage = "原来是这样，我之前还以为你忘了我们的约定。",
        )))
        val query = ChatMemorySelector.recallQuery("嗯", context).orEmpty()
        assertTrue(query.contains("医院"))
        assertTrue(query.contains("约定"))
        assertNull(ChatMemorySelector.recallQuery("嗯", ChatContextState()))
        assertNull(ChatMemorySelector.recallQuery("别再提那件事", context))
    }

    @Test fun resolvedEmotionsAndConflictsUpdateTogetherAndRetainTheirSource() {
        val planner = ChatInteractionPlanner(Json { ignoreUnknownKeys = true })
        val plan = planner.parse(
            """{"state":{"mood":"","currentAgenda":"","dynamics":{"unresolvedConflict":""}},"stateEvidence":{"mood":"误会已经说开了","dynamics.unresolvedConflict":"误会已经说开了"},"turnSignificance":"MINOR"}""",
            previous = ChatCharacterState(mood = "难过", currentAgenda = "等待解释",
                dynamics = RelationshipDynamics(unresolvedConflict = "失约未解释")),
            userMessage = "误会已经说开了", assistantMessage = "那就说好了。",
        )!!
        assertEquals("自然", plan.state.mood)
        assertEquals("", plan.state.currentAgenda)
        assertEquals("", plan.state.dynamics.unresolvedConflict)
        val state = plan.state.bindStateObservationSources(listOf(ChatPendingTurn(
            sequence = 7L, userMessageId = "u7", assistantMessageId = "a7",
            userMessage = "误会已经说开了", assistantMessage = "那就说好了。",
        )))
        val mood = state.stateObservations.single { it.field == "mood" }
        assertEquals("误会已经说开了", mood.basis)
        assertFalse(mood.interpretation)
        assertEquals("u7", mood.sourceUserMessageId)
        assertEquals(7L, mood.sourceSequence)
        assertEquals(state, Json.decodeFromString(ChatCharacterState.serializer(),
            Json.encodeToString(ChatCharacterState.serializer(), state)))
    }

    @Test fun anUnverifiedQuotationRemainsAContextualInterpretation() {
        val state = observeCharacterStateChanges(ChatCharacterState(),
            ChatCharacterState(currentUserImpression = "我怀疑他还在生气"),
            mapOf("currentUserImpression" to "用户承认自己生气"), "嗯", "你今天话很少。")
        val impression = state.stateObservations.single()
        assertTrue(impression.interpretation)
        assertFalse(impression.basis.contains("用户承认"))
        assertEquals(0L, impression.sourceSequence)
    }

    @Test fun authoredVirtualAssistantsKeepTheirIdentityButAHumanDoesNotInheritItFromLore() {
        val character = PersonaProfile(name = "小屏", coreIdentity = "她是住在屏幕里的虚拟助手。")
        assertTrue(PersonaImmersionPolicy.findReplyViolations(character, "我是虚拟助手。").isEmpty())
        assertTrue(PersonaImmersionPolicy.findReplyViolations(PersonaProfile(name = "阿青", coreIdentity = "人类剑客"),
            "我是虚拟助手。").isNotEmpty())
        assertTrue(PersonaImmersionPolicy.findReplyViolations(character, "我是ChatGPT。").isNotEmpty())
    }
}
