package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import com.labteto.dshmobile.local.model.modelPostAdmissionFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInteractionPlannerTest {

    @Test
    fun postTurnAndSuggestionPromptsRespectCurrentStoryStage() {
        val persona = PersonaProfile(
            name = "青岚",
            coreIdentity = "小镇的书店店员",
            facts = listOf(
                CharacterFact("known", CharacterFactCategories.PERSONALITY,
                    "性格沉稳", provenance = CharacterFactProvenance.CANON),
                CharacterFact("future", CharacterFactCategories.PERSONALITY,
                    "第二幕掌握钟楼密钥", temporalScope = "act-2",
                    provenance = CharacterFactProvenance.CANON),
                CharacterFact("future-relationship", CharacterFactCategories.RELATIONSHIPS,
                    "结局时揭开阿宁的秘密", temporalScope = "act-2"),
            ),
        )
        val before = ChatContextState()
        val after = ChatContextState(storyStage = "act-2")
        val state = ChatCharacterState()
        val earlyPlanner = planner.prompt(persona, state, "你好", "你好", context = before)
        val latePlanner = planner.prompt(persona, state, "你好", "你好", context = after)
        val earlySuggestion = planner.suggestionsPrompt(
            persona, state, "你好", "你好", context = before,
        )
        val lateSuggestion = planner.suggestionsPrompt(
            persona, state, "你好", "你好", context = after,
        )
        assertTrue(earlyPlanner.contains("性格沉稳"))
        assertFalse(earlyPlanner.contains("钟楼密钥"))
        assertFalse(earlySuggestion.contains("钟楼密钥"))
        assertFalse(earlySuggestion.contains("阿宁的秘密"))
        assertFalse(earlyPlanner.contains("旧版预告"))
        assertFalse(earlySuggestion.contains("旧版预告"))
        assertTrue(latePlanner.contains("钟楼密钥"))
        assertTrue(lateSuggestion.contains("阿宁的秘密"))
    }


    @Test
    fun legacyRelationshipScoresAndPersonalityMetricsArePreservedButNotModified() {
        val saved = ChatCharacterState(
            initiative = 84,
            shareDesire = 21,
            dynamics = RelationshipDynamics(
                stage = "AMBIGUOUS", warmth = 75, trust = 63,
                reciprocity = 42, tension = 68, stability = 39,
                unresolvedConflict = "昨天的误会",
            ),
            userPattern = UserChatPattern(
                replyLength = "long", directness = 88, playfulness = 25,
                initiative = 64, emojiStyle = "常用表情", preferredTone = "直白",
            ),
        )
        val patch = """{"state":{"mood":"开心","initiative":0,"shareDesire":100,
            "dynamics":{"warmth":0,"trust":100,"reciprocity":100,"tension":0,"stability":0},
            "userPattern":{"replyLength":"short","directness":0,"playfulness":100,
                "initiative":100,"emojiStyle":"不用表情","preferredTone":"夸张"}},
            "turnSignificance":"MINOR"}"""
        val result = planner.parse(patch, saved, userMessage = "你好", assistantMessage = "你好")!!
        assertEquals("开心", result.state.mood)
        assertEquals(saved.initiative, result.state.initiative)
        assertEquals(saved.shareDesire, result.state.shareDesire)
        assertEquals(saved.dynamics, result.state.dynamics)
        assertEquals(saved.userPattern, result.state.userPattern)
        val normalized = normalizeChatPostTurnPatch(
            Json.parseToJsonElement(patch).jsonObject,
        )
        val state = normalized["state"]!!.jsonObject
        assertFalse(state.containsKey("initiative"))
        assertFalse(state.containsKey("shareDesire"))
        assertFalse(state.containsKey("userPattern"))
        assertFalse(state["dynamics"]!!.jsonObject.containsKey("warmth"))
    }

    @Test
    fun groundedNaturalRelationshipStatementCanAdvanceWithoutRetiredStageTriggerPhrases() {
        val previous = ChatCharacterState(dynamics = RelationshipDynamics(stage = "FAMILIAR"))
        val statement = "我们决定成为彼此最重要的伴侣"
        val patch = """{"state":{"dynamics":{"stage":"COMMITTED"}},"stateEvidence":{"dynamics.stage":"我们决定成为彼此最重要的伴侣"},"turnSignificance":"MAJOR"}"""
        val next = planner.parse(patch, previous, userMessage = statement, assistantMessage = "好")!!
        assertEquals("COMMITTED", next.state.dynamics.stage)

        val unrelated = planner.parse(patch, previous, userMessage = "我朋友说" + statement,
            assistantMessage = "哦")!!
        assertEquals("FAMILIAR", unrelated.state.dynamics.stage)
        val fabricated = planner.parse(patch, previous, userMessage = "我们今天吃过饭了",
            assistantMessage = "嗯")!!
        assertEquals("FAMILIAR", fabricated.state.dynamics.stage)
        val wrongDirection = planner.parse(
            """{"state":{"dynamics":{"stage":"COMMITTED"}},"stateEvidence":{"dynamics.stage":"我们分手了"},"turnSignificance":"MAJOR"}""",
            previous, userMessage = "我们分手了", assistantMessage = "好",
        )!!
        assertEquals("FAMILIAR", wrongDirection.state.dynamics.stage)
    }

    @Test
    fun retiredScoresCannotEnterRuntimePromptsAndReplySuggestionsRemainAvailable() {
        val persona = PersonaProfile(name = "阿青")
        val state = ChatCharacterState(
            initiative = 87,
            shareDesire = 91,
            dynamics = RelationshipDynamics(warmth = 77, trust = 78),
            userPattern = UserChatPattern(directness = 95, playfulness = 90, initiative = 93),
        )
        val postPrompt = planner.prompt(persona, state, "你好", "你好")
        val suggestionPrompt = planner.suggestionsPrompt(persona, state, "你好", "你好")
        listOf("warmth/trust", "userPattern(", "用户习惯：", "主动欲").forEach {
            assertFalse(postPrompt.contains(it))
        }
        assertFalse(suggestionPrompt.contains("用户习惯："))
        assertTrue(suggestionPrompt.contains("最近对话") || suggestionPrompt.contains("用户："))
        assertTrue(postPrompt.contains("更新人物隐藏状态"))
    }

    @Test
    fun confirmedDatingExpressionsAdvanceRelationshipWithoutRigidKeywordRejection() {
        val previous = ChatCharacterState(dynamics = RelationshipDynamics(stage = "FAMILIAR"))
        val patch = """{"state":{"dynamics":{"stage":"DATING"}},"turnSignificance":"MAJOR"}"""
        listOf("我们决定交往了", "我们开始交往了", "我们正在交往").forEach { user ->
            val result = planner.parse(
                patch, previous, userMessage = user, assistantMessage = "好，我们说定了",
            )!!
            assertEquals("DATING", result.state.dynamics.stage)
        }
    }

    @Test
    fun confirmedCoupleExpressionsAdvanceButNegatedAndHypotheticalDoNot() {
        val previous = ChatCharacterState(dynamics = RelationshipDynamics(stage = "DATING"))
        val patch = """{"state":{"dynamics":{"stage":"COMMITTED"}},"turnSignificance":"MAJOR"}"""
        listOf("我们已经是情侣了", "我们成为情侣了", "我们确定恋爱关系了").forEach { user ->
            val result = planner.parse(
                patch, previous, userMessage = user, assistantMessage = "嗯",
            )!!
            assertEquals("COMMITTED", result.state.dynamics.stage)
        }
        listOf("我们还没成为情侣", "如果我们成为情侣", "我朋友已经是情侣了").forEach { user ->
            val result = planner.parse(
                patch, previous, userMessage = user, assistantMessage = "知道了",
            )!!
            assertEquals("DATING", result.state.dynamics.stage)
        }
    }

    @Test
    fun topicSwitchWordsDoNotInventResolutionOfExistingEvents() {
        val previous = ChatCharacterState(
            currentFocus = "未解决的旧话题",
            currentAgenda = "继续旧讨论",
            unresolvedThreads = listOf("旧线索"),
        )
        val patch = """{"state":{},"turnSignificance":"NONE"}"""
        listOf("我想换个话题", "我们先换个话题", "我想先不聊这个", "我希望换个话题").forEach { user ->
            val state = planner.parse(patch, previous, userMessage = user, assistantMessage = "好")!!.state
            assertEquals(previous.currentFocus, state.currentFocus)
            assertEquals(previous.currentAgenda, state.currentAgenda)
            assertEquals(previous.unresolvedThreads, state.unresolvedThreads)
        }
        listOf("我不想换个话题", "昨天他说我想换个话题").forEach { user ->
            val state = planner.parse(patch, previous, userMessage = user, assistantMessage = "好")!!.state
            assertEquals("未解决的旧话题", state.currentFocus)
            assertEquals(listOf("旧线索"), state.unresolvedThreads)
        }
    }


    @Test
    fun unresolvedStatesSurviveOrdinaryTurnsUntilActuallyUpdated() {
        var state = ChatCharacterState(
            currentFocus = "刚才的争执",
            immediateConcern = "担心用户生气",
            unresolvedThreads = listOf("要不要继续争执"),
        )
        repeat(4) {
            state = planner.parse(
                """{"state":{},"suggestions":[],"turnSignificance":"NONE"}""",
                previous = state,
                userMessage = "嗯",
                assistantMessage = "好",
            )!!.state
        }

        assertEquals("刚才的争执", state.currentFocus)
        assertEquals("担心用户生气", state.immediateConcern)
        assertTrue(state.unresolvedThreads.isNotEmpty())
    }

    @Test
    fun casualTopicWordsDoNotEraseOpenThreads() {
        val previous = ChatCharacterState(
            currentFocus = "关于工作的争执",
            currentAgenda = "等待解释",
            unresolvedThreads = listOf("周末约定"),
        )
        listOf("算了，先去吃饭", "我们说正事吧", "昨天他说换个话题，我不认可").forEach { user ->
            val result = planner.parse(
                """{"state":{},"turnSignificance":"NONE"}""",
                previous = previous,
                userMessage = user,
                assistantMessage = "好",
            )!!
            assertEquals("关于工作的争执", result.state.currentFocus)
            assertEquals(listOf("周末约定"), result.state.unresolvedThreads)
        }
    }

    @Test
    fun relationshipCommitmentSupportsNaturalConfirmedLanguage() {
        val previous = ChatCharacterState(dynamics = RelationshipDynamics(stage = "DATING"))
        val plan = planner.parse(
            """{"state":{"dynamics":{"stage":"COMMITTED"}},"turnSignificance":"MAJOR"}""",
            previous,
            userMessage = "我们决定在一起",
            assistantMessage = "嗯，这次我们是认真的",
        )!!
        assertEquals("COMMITTED", plan.state.dynamics.stage)
    }

    @Test
    fun negatedQuestionedOrThirdPartyEventsDoNotChangeOurRelationshipStage() {
        val separated = ChatCharacterState(dynamics = RelationshipDynamics(stage = "COMMITTED"))
        val breakup = """{"state":{"dynamics":{"stage":"SEPARATED"}},"turnSignificance":"MAJOR"}"""
        listOf(
            "我们没有分手",
            "我们不会分手",
            "我们分手了吗？",
            "我们昨天听说前任分手了",
            "假如我们分手了会怎样",
        ).forEach { user ->
            val result = planner.parse(breakup, separated, userMessage = user, assistantMessage = "知道了")!!
            assertEquals("COMMITTED", result.state.dynamics.stage)
        }
        val confirmed = planner.parse(
            breakup, separated, userMessage = "我们昨天决定分手了", assistantMessage = "好",
        )!!
        assertEquals("SEPARATED", confirmed.state.dynamics.stage)
    }

    @Test
    fun stageEventMustMatchRequestedRelationshipDirection() {
        val previous = ChatCharacterState(dynamics = RelationshipDynamics(stage = "FAMILIAR"))
        val payload = """{"state":{"dynamics":{"stage":"COMMITTED"}},"turnSignificance":"MAJOR"}"""
        val wrongContext = planner.parse(
            payload, previous, userMessage = "我的前任结婚了", assistantMessage = "哦",
        )!!
        assertEquals("FAMILIAR", wrongContext.state.dynamics.stage)
        val wrongDirection = planner.parse(
            payload, previous, userMessage = "我们分手了", assistantMessage = "好",
        )!!
        assertEquals("FAMILIAR", wrongDirection.state.dynamics.stage)
        val confirmed = planner.parse(
            payload, previous, userMessage = "我们确认关系，在一起了", assistantMessage = "好",
        )!!
        assertEquals("COMMITTED", confirmed.state.dynamics.stage)
    }

    @Test
    fun backgroundConsolidationRegeneratesOnlyRecoverableAdmittedStreamFailures() {
        val interrupted = modelPostAdmissionFailure(
            code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
            detail = "模型流中断",
        )
        assertTrue(shouldRetryChatPostTurnRequest(interrupted))
        assertTrue(shouldRetryChatPostTurnRequest(LocalModelException(
            code = "MODEL_NETWORK", message = "请求体已发送，等待响应时断开",
            retryable = false, admissionState = LocalModelAdmissionState.MAYBE_ADMITTED,
            continuationEligible = true,
        )))
        assertTrue(shouldRetryChatPostTurnRequest(LocalModelException(
            code = "MODEL_NETWORK", message = "连接前失败", retryable = true,
        )))
        assertEquals(false, shouldRetryChatPostTurnRequest(modelPostAdmissionFailure(
            code = "MODEL_STREAM_PROTOCOL",
            detail = "协议错误",
        )))
        assertEquals(false, shouldRetryChatPostTurnRequest(LocalModelException(
            code = "MODEL_HTTP_401", message = "身份验证失败", retryable = false,
        )))
    }

    @Test
    fun systemOwnedFieldsAreIgnoredWithoutFalseParseFailureOrRetry() {
        val parser = ChatInteractionPlanParser(Json { ignoreUnknownKeys = true })
        listOf(
            """{"state":{"scene":{"location":"厨房"}},"turnSignificance":"MINOR"}""",
            """{"state":{"evolution":{"traits":{"courage":99}}}}""",
            """{"state":{"dynamics":{"systemCounter":3},"continuity":{"evidence":"runtime"}}}""",
        ).forEach { payload ->
            assertNotNull(parser.parsePlan(payload))
        }
    }

    @Test
    fun invalidStateCannotBeMaskedBySuggestionsOrUnusableDiary() {
        val parser = ChatInteractionPlanParser(Json { ignoreUnknownKeys = true })
        val withSuggestion = """{"state":{"mood":42},
            "suggestions":[{"label":"聊聊","text":"你好"}],"turnSignificance":"MINOR"}"""
        assertEquals("state_patch_unusable", parser.parseFailureKind(withSuggestion))
        val withoutPerspective = """{"state":{"mood":42},
            "diaryDelta":{"event":"今天发生了一件重要的事情","importance":4},
            "turnSignificance":"MINOR"}"""
        assertEquals("state_patch_unusable", parser.parseFailureKind(withoutPerspective))
    }

    @Test
    fun missingStateAndEmptyResponseCannotSilentlySettlePendingChatTurns() {
        val parser = ChatInteractionPlanParser(Json { ignoreUnknownKeys = true })
        assertEquals("state_patch_missing", parser.parseFailureKind("{}"))
        assertEquals("state_patch_missing", parser.parseFailureKind(
            """{"turnSignificance":"MAJOR"}""",
        ))
        assertNotNull(parser.parsePlan("""{"state":{},"turnSignificance":"NONE"}"""))
    }

    @Test
    fun invalidStateCannotSilentlyAdvancePendingTurnCursor() {
        val parser = ChatInteractionPlanParser(Json { ignoreUnknownKeys = true })
        val bad = """{"state":{"mood":42,"dynamics":{"facts":["无来源文字"]}},"turnSignificance":"MINOR"}"""
        assertEquals(null, parser.parsePlan(bad))
        assertEquals("state_patch_unusable", parser.parseFailureKind(bad))
        assertNotNull(parser.parsePlan("""{"state":{},"turnSignificance":"NONE"}"""))
        assertNotNull(parser.parsePlan(
            """{"state":{"mood":42},"diaryDelta":{"event":"真正发生的事情","importance":4,"feeling":"当时很开心"},"turnSignificance":"MINOR"}""",
        ))
    }

    @Test
    fun changingTheTopicDoesNotAutomaticallyResolveUnfinishedEvents() {
        val previous = ChatCharacterState(currentFocus = "旧话题", unresolvedThreads = listOf("旧线索"))
        val state = planner.parse("""{"state":{},"turnSignificance":"NONE"}""",
            previous, userMessage = "我们换个话题吧，聊点新的", assistantMessage = "好")!!.state
        assertEquals(previous.currentFocus, state.currentFocus)
        assertEquals(previous.unresolvedThreads, state.unresolvedThreads)
    }

    @Test
    fun onlyTheExplicitStatePatchResolvesAnEvent() {
        val previous = ChatCharacterState(mood = "难过", currentFocus = "旧事",
            currentAgenda = "等待解释", unresolvedThreads = listOf("未完成的约定"))
        val state = planner.parse("""{"state":{"currentFocus":"","mood":"自然"},"turnSignificance":"MINOR"}""",
            previous, userMessage = "我们把这件事说清了", assistantMessage = "嗯，这次说开了")!!.state
        assertEquals("自然", state.mood)
        assertEquals("", state.currentFocus)
        assertEquals(previous.currentAgenda, state.currentAgenda)
        assertEquals(previous.unresolvedThreads, state.unresolvedThreads)
    }

    @Test
    fun noneSignificanceStillHonorsExplicitTransientClears() {
        val previous = ChatCharacterState(
            mood = "平静",
            currentFocus = "旧话题",
            recentImpression = "还在介意",
            currentUserImpression = "还在介意",
            activeGoal = "继续追问",
            currentAgenda = "把旧事说完",
            internalConflict = "想问又不想问",
            immediateConcern = "怕对方回避",
            unresolvedThreads = listOf("旧线索"),
        )
        val state = planner.parse(
            """{"state":{"mood":"生气","currentFocus":"","recentImpression":"","activeGoal":"","currentAgenda":"","internalConflict":"","immediateConcern":"","unresolvedThreads":[]},"suggestions":[],"turnSignificance":"NONE"}""",
            previous = previous,
            userMessage = "没事了",
            assistantMessage = "好",
        )!!.state

        assertEquals("平静", state.mood)
        assertTrue(state.currentFocus.isBlank())
        assertEquals("还在介意", state.currentUserImpression)
        assertTrue(state.recentImpression.isBlank())
        assertTrue(state.activeGoal.isBlank())
        assertTrue(state.currentAgenda.isBlank())
        assertTrue(state.internalConflict.isBlank())
        assertTrue(state.immediateConcern.isBlank())
        assertTrue(state.unresolvedThreads.isEmpty())
    }

    @Test
    fun retiredModeFromSavedStoryCannotAppearInAuxiliaryPromptOrResumeState() {
        val legacy = ChatCharacterState(
            mood = "疲倦",
            interactionIntent = "STRATEGIST",
            interactionIntentStrength = 3,
            interactionIntensity = 2,
            recentVerbalTags = listOf("直球"),
        )
        val migrated = legacy.canonicalizeLegacyCharacterState()
        assertEquals(ChatInteractionIntent.NORMAL.name, migrated.interactionIntent)
        assertEquals(0, migrated.interactionIntentStrength)
        assertEquals(0, migrated.interactionIntensity)
        assertEquals("疲倦", migrated.mood)
        assertEquals(listOf("直球"), migrated.recentVerbalTags)

        val suggestion = planner.suggestionsPrompt(
            PersonaProfile(name = "阿青"), legacy, "你好", "好",
        )
        assertFalse(suggestion.contains("STRATEGIST"))
        assertTrue(suggestion.contains("人物当前情绪=疲倦"))

        val resumed = planner.applyDeterministicInteractionState(legacy, "", "好")
        assertEquals(ChatInteractionIntent.NORMAL.name, resumed.interactionIntent)
        assertEquals(0, resumed.interactionIntentStrength)
        assertEquals(0, resumed.interactionIntensity)

        val active = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.FLIRTING.name,
            interactionIntentStrength = 2,
            interactionIntensity = 3,
        )
        assertEquals(active, active.canonicalizeLegacyCharacterState())

        val unrelatedStoredState = ChatCharacterState(
            interactionIntent = "FUTURE_MODE",
            interactionIntentStrength = 4,
            interactionIntensity = 3,
        )
        assertEquals(unrelatedStoredState, unrelatedStoredState.canonicalizeLegacyCharacterState())
    }

    private val planner = ChatInteractionPlanner(Json { ignoreUnknownKeys = true })

    @Test
    fun plannerAndSuggestionsHonorCorrectionsWithoutFixedExpressionRecipe() {
        val persona = PersonaProfile(
            name = "阿青",
            coreIdentity = "嘴硬心软",
            corrections = listOf("不善于拒绝，会服软，很多推辞只是她自己的表达方式"),
        )
        val state = ChatCharacterState(
            relationshipState = "亲近",
            dynamics = RelationshipDynamics(stage = "AMBIGUOUS"),
            interactionIntent = ChatInteractionIntent.FLIRTING.name,
            interactionIntentStrength = 2,
            interactionIntensity = 3,
        )

        val plannerPrompt = planner.prompt(
            persona = persona,
            state = state,
            userMessage = "你又嘴硬了",
            assistantMessage = "谁嘴硬了……你少得意。",
        )
        val suggestionsPrompt = planner.suggestionsPrompt(
            persona = persona,
            state = state,
            userMessage = "你又嘴硬了",
            assistantMessage = "谁嘴硬了……你少得意。",
        )

        assertTrue(plannerPrompt.contains("用户纠正（最高优先）"))
        assertTrue(plannerPrompt.contains("不善于拒绝，会服软"))
        assertTrue(!plannerPrompt.contains("表达解释：角色存在嘴硬"))
        assertTrue(plannerPrompt.contains("人物实际表达"))
        assertTrue(suggestionsPrompt.contains("用户纠正（最高优先）"))
        assertTrue(suggestionsPrompt.contains("不把单个模糊推辞自动解释为关系拒绝"))
        assertTrue(suggestionsPrompt.contains("清晰明确停止、退出或拒绝继续一次即生效"))
    }

    @Test
    fun genericRestraintAloneDoesNotEnableIndirectRefusalSemantics() {
        val persona = PersonaProfile(
            name = "阿青",
            coreIdentity = "克制谨慎，表达含蓄",
        )
        val prompt = planner.prompt(
            persona = persona,
            state = ChatCharacterState(),
            userMessage = "今晚聊聊吗",
            assistantMessage = "可以。",
        )

        assertTrue(!prompt.contains("表达解释：角色存在"))
    }

    @Test
    fun trackerRecordsActualExpressionsAndUserDraftRespectsExplicitBoundaries() {
        val persona = PersonaProfile(
            name = "阿青",
            coreIdentity = "嘴硬心软",
        )
        val plannerPrompt = planner.prompt(
            persona = persona,
            state = ChatCharacterState(),
            userMessage = "停下",
            assistantMessage = "好。",
        )
        val suggestionsPrompt = planner.suggestionsPrompt(
            persona = persona,
            state = ChatCharacterState(),
            userMessage = "停下",
            assistantMessage = "好。",
        )

        assertTrue(plannerPrompt.contains("人物实际表达"))
        assertFalse(plannerPrompt.contains("一次表达即生效，不要求重复"))
        assertTrue(suggestionsPrompt.contains("一次即生效"))
    }

    @Test
    fun parsesAndBoundsStateAndSuggestions() {
        val previous = ChatCharacterState(
            mood = "平静",
            relationshipState = "熟悉",
            initiative = 50,
            shareDesire = 50,
        )
        val payload = """
            {
              "state":{
                "mood":"有点不爽",
                "relationshipState":"更熟了一点",
                "currentFocus":"用户昨天失约",
                "recentImpression":"嘴硬",
                "unresolvedThreads":["昨天为什么没来","昨天为什么没来","吃饭"],
                "initiative":120,
                "shareDesire":-3,
                "dynamics":{
                  "stage":"FAMILIAR",
                  "warmth":95,
                  "trust":95,
                  "reciprocity":95,
                  "tension":80,
                  "stability":95
                }
              },
              "suggestions":[
                {"label":"顺着问","style":"自然","text":"昨天那事你还记着啊？那我现在补个解释。","bold":false},
                {"label":"轻轻逗","style":"俏皮","text":"哟，还记仇呢？要不要先收点利息。","bold":false},
                {"label":"直接说","style":"直球","text":"你要真介意，我现在就认真跟你说清楚。","bold":false},
                {"label":"放飞一下","style":"放飞","text":"行，今日开庭，我申请用一顿饭贿赂主审大人。","bold":true},
                {"label":"重复","style":"俏皮","text":"哟，还记仇呢？要不要先收点利息。","bold":false}
              ]
            }
        """.trimIndent()

        val result = planner.parse(payload, previous)
        assertNotNull(result)
        val plan = result!!
        assertEquals("有点不爽", plan.state.mood)
        assertEquals(50, plan.state.initiative)
        assertEquals(50, plan.state.shareDesire)
        assertEquals(50, plan.state.dynamics.warmth)
        assertEquals(50, plan.state.dynamics.trust)
        assertEquals(2, plan.state.unresolvedThreads.size)
        assertEquals(4, plan.suggestions.size)
        assertEquals(plan.suggestions.map { it.text }.distinct().size, plan.suggestions.size)
        assertTrue(plan.suggestions.any { it.bold && it.style == "放飞" })
        assertTrue(plan.suggestions.all { it.text.isNotBlank() })
        assertTrue(plan.state.updatedAt > 0L)
    }

    @Test
    fun legacyReplyDraftStillWorksAfterDirectionFieldsAreRetired() {
        val previous = ChatCharacterState()
        val payload = """{"state":{"mood":"自然"},"suggestions":[{"label":"自然接话","text":"那你继续说，我听着。","direction":"旧剧情方向","impact":"旧影响"}]}"""

        val plan = planner.parse(payload, previous)!!
        assertEquals(1, plan.suggestions.size)
        assertEquals("那你继续说，我听着。", plan.suggestions.single().text)
    }

    @Test
    fun unverifiedRelationDescriptionCannotBypassStageValidation() {
        val previous = ChatCharacterState(relationshipState = "熟悉中",
            dynamics = RelationshipDynamics(stage = "FAMILIAR"))
        val unverified = """{"state":{"relationshipState":"已经结婚",
            "dynamics":{"stage":"COMMITTED"}},"turnSignificance":"MAJOR"}"""
        val rejected = planner.parse(unverified, previous,
            userMessage = "我们今天吃了饭", assistantMessage = "挺开心的")!!
        assertEquals("FAMILIAR", rejected.state.dynamics.stage)
        assertEquals("熟悉中", rejected.state.relationshipState)
        val bypass = planner.parse("""{"state":{"relationshipState":"已经结婚"},
            "stateEvidence":{"relationshipState":"我们今天吃了饭"},
            "turnSignificance":"MAJOR"}""", previous,
            userMessage = "我们今天吃了饭", assistantMessage = "挺开心的")!!
        assertEquals("FAMILIAR", bypass.state.dynamics.stage)
        assertEquals("熟悉中", bypass.state.relationshipState)
        val ungrounded = planner.parse("""{"state":{"relationshipState":"相处变得更自在"},
            "turnSignificance":"MINOR"}""", previous,
            userMessage = "今天好热", assistantMessage = "嗯")!!
        assertEquals("熟悉中", ungrounded.state.relationshipState)
        val grounded = planner.parse("""{"state":{"relationshipState":"相处变得更自在"},
            "stateEvidence":{"relationshipState":"我们相处变得更自在了"},
            "turnSignificance":"MINOR"}""", previous,
            userMessage = "我们相处变得更自在了", assistantMessage = "是啊")!!
        assertEquals("相处变得更自在", grounded.state.relationshipState)
        assertEquals("FAMILIAR", grounded.state.dynamics.stage)
    }

    @Test
    fun acceptedRelationshipStageAndDescriptionShareTheSameSourcedTransition() {
        val previous = ChatCharacterState(relationshipState = "熟悉中",
            dynamics = RelationshipDynamics(stage = "FAMILIAR"))
        val next = planner.parse("""{"state":{"dynamics":{"stage":"COMMITTED"},
            "relationshipState":"随意描述"},
            "stateEvidence":{"dynamics.stage":"我们确认关系，在一起了"},
            "turnSignificance":"MAJOR"}""", previous,
            userMessage = "我们确认关系，在一起了", assistantMessage = "好")!!.state
        assertEquals("COMMITTED", next.dynamics.stage)
        assertEquals("稳定关系", next.relationshipState)
        val obs = next.stateObservations.associateBy { it.field }
        assertEquals("FAMILIAR", obs["dynamics.stage"]?.previousValue)
        assertEquals("COMMITTED", obs["dynamics.stage"]?.value)
        assertEquals("熟悉中", obs["relationshipState"]?.previousValue)
    }

    @Test
    fun stageCannotJumpWithoutExplicitRelationshipEvidence() {
        val previous = ChatCharacterState(
            relationshipState = "熟悉中",
            dynamics = RelationshipDynamics(stage = "FAMILIAR"),
        )
        val payload = """
            {
              "state":{
                "relationshipState":"稳定关系",
                "dynamics":{
                  "stage":"COMMITTED",
                  "warmth":60,
                  "trust":60,
                  "reciprocity":60,
                  "tension":10,
                  "stability":60
                }
              },
              "suggestions":[]
            }
        """.trimIndent()

        val plan = planner.parse(
            payload,
            previous,
            userMessage = "她今天回复挺快的",
            assistantMessage = "那你还挺开心",
        )!!

        assertEquals("FAMILIAR", plan.state.dynamics.stage)
        assertEquals("熟悉中", plan.state.relationshipState)
    }

    @Test
    fun explicitRelationshipEventAllowsStageTransition() {
        val previous = ChatCharacterState(
            relationshipState = "暧昧期",
            dynamics = RelationshipDynamics(stage = "AMBIGUOUS"),
        )
        val payload = """
            {
              "state":{
                "relationshipState":"稳定关系",
                "dynamics":{
                  "stage":"COMMITTED",
                  "warmth":72,
                  "trust":70,
                  "reciprocity":68,
                  "tension":8,
                  "stability":70
                }
              },
              "suggestions":[]
            }
        """.trimIndent()

        val plan = planner.parse(
            payload,
            previous,
            userMessage = "我们刚刚确认关系，在一起了",
            assistantMessage = "嗯，终于说开了",
        )!!

        assertEquals("COMMITTED", plan.state.dynamics.stage)
        assertEquals("稳定关系", plan.state.relationshipState)
    }

    @Test
    fun factsAndUnknownsStaySeparatedAndUngroundedHypothesesAreDiscarded() {
        val previous = ChatCharacterState()
        val payload = """
            {
              "state":{
                "dynamics":{
                  "stage":"FAMILIAR",
                  "warmth":50,
                  "trust":50,
                  "reciprocity":50,
                  "tension":10,
                  "stability":50,
                  "facts":[
                    {"text":"对方明确说周末有空","confidence":96,"source":"user"},
                    {"text":"她大概很喜欢用户","confidence":99,"source":"inference"}
                  ],
                  "hypotheses":[
                    {"text":"她可能愿意继续了解","confidence":65,"source":"inference"}
                  ],
                  "unknowns":["她是否愿意单独约会"]
                }
              },
              "suggestions":[]
            }
        """.trimIndent()

        val plan = planner.parse(
            payload,
            previous,
            userMessage = "她明确说周末有空，我还没约",
        )!!

        assertEquals(1, plan.state.dynamics.facts.size)
        assertEquals("对方明确说周末有空", plan.state.dynamics.facts.single().text)
        assertEquals(0, plan.state.dynamics.hypotheses.size)
        assertEquals(1, plan.state.dynamics.unknowns.size)
    }

    @Test
    fun claimedUserFactWithoutTextEvidenceIsRejected() {
        val previous = ChatCharacterState()
        val payload = """
            {
              "state":{
                "dynamics":{
                  "stage":"FAMILIAR",
                  "facts":[
                    {"text":"对方明确答应周末单独约会","confidence":99,"source":"user"}
                  ]
                }
              },
              "suggestions":[]
            }
        """.trimIndent()

        val plan = planner.parse(
            payload,
            previous,
            userMessage = "她今天只发了一个表情",
        )!!

        assertTrue(plan.state.dynamics.facts.isEmpty())
    }

    @Test
    fun retiredUserPatternScoresCannotBeRewritten() {
        val previous = ChatCharacterState(
            userPattern = UserChatPattern(
                replyLength = "short",
                directness = 40,
                playfulness = 40,
                initiative = 40,
            ),
        )
        val payload = """
            {
              "state":{
                "userPattern":{
                  "replyLength":"long",
                  "directness":100,
                  "playfulness":100,
                  "initiative":100,
                  "emojiStyle":"偶尔用",
                  "preferredTone":"自然"
                }
              },
              "suggestions":[]
            }
        """.trimIndent()

        val plan = planner.parse(payload, previous)!!

        assertEquals("short", plan.state.userPattern.replyLength)
        assertEquals(40, plan.state.userPattern.directness)
        assertEquals(40, plan.state.userPattern.playfulness)
        assertEquals(40, plan.state.userPattern.initiative)
        assertEquals("", plan.state.userPattern.emojiStyle)
        assertEquals("", plan.state.userPattern.preferredTone)
    }

    @Test
    fun retiredMessageLengthProfileIsNotUpdatedByBackgroundModel() {
        val previous = ChatCharacterState(
            userPattern = UserChatPattern(
                replyLength = "long",
                observedTurns = 3,
                averageMessageChars = 12,
            ),
        )
        val payload = """
            {
              "state":{
                "userPattern":{
                  "replyLength":"long",
                  "directness":50,
                  "playfulness":50,
                  "initiative":50
                }
              },
              "suggestions":[]
            }
        """.trimIndent()

        val plan = planner.parse(
            payload,
            previous,
            userMessage = "嗯，行",
        )!!

        assertEquals(previous.userPattern, plan.state.userPattern)
    }

    @Test
    fun partialPlannerPayloadPreservesOmittedState() {
        val previous = ChatCharacterState(
            mood = "有点委屈",
            relationshipState = "暧昧期",
            currentFocus = "昨天的失约",
            initiative = 72,
            shareDesire = 66,
            dynamics = RelationshipDynamics(
                stage = "AMBIGUOUS",
                warmth = 73,
                trust = 64,
                reciprocity = 61,
                tension = 29,
                stability = 58,
                unresolvedConflict = "失约还没解释",
            ),
            userPattern = UserChatPattern(
                replyLength = "short",
                directness = 62,
                playfulness = 71,
                initiative = 59,
            ),
        )
        val payload = """
            {
              "state":{
                "mood":"缓和了一点"
              },
              "suggestions":[
                {"label":"自然","text":"行，那你先忙。"}
              ]
            }
        """.trimIndent()

        val plan = planner.parse(payload, previous)!!

        assertEquals("缓和了一点", plan.state.mood)
        assertEquals("暧昧期", plan.state.relationshipState)
        assertEquals("昨天的失约", plan.state.currentFocus)
        assertEquals(72, plan.state.initiative)
        assertEquals(66, plan.state.shareDesire)
        assertEquals(previous.dynamics, plan.state.dynamics)
        assertEquals(previous.userPattern, plan.state.userPattern)
    }

    @Test
    fun noOpTurnPreservesStateWithoutAnExpiryCountdown() {
        val previous = ChatCharacterState(
            mood = "开心",
            relationshipState = "熟悉中",
            activeGoal = "等用户把昨天的话说完",
            currentAgenda = "先听",
            internalConflict = "想追问，但不想逼得太紧",
            immediateConcern = "用户昨天提到的事",
            initiative = 68,
            dynamics = RelationshipDynamics(warmth = 70, trust = 64),
            updatedAt = 1234L,
        )
        val payload = """
            {
              "state":{
                "mood":"突然兴奋",
                "activeGoal":"立刻表白",
                "initiative":100
              },
              "suggestions":[
                {"label":"接着聊","style":"自然","text":"你刚才笑什么？说来听听。","bold":false}
              ],
              "turnSignificance":"NONE"
            }
        """.trimIndent()

        val plan = planner.parse(
            payload,
            previous,
            userMessage = "哈哈",
            assistantMessage = "笑什么？",
        )!!

        assertEquals("NONE", plan.turnSignificance)
        assertEquals(previous.mood, plan.state.mood)
        assertEquals(previous.relationshipState, plan.state.relationshipState)
        assertEquals(previous.activeGoal, plan.state.activeGoal)
        assertEquals(previous.currentAgenda, plan.state.currentAgenda)
        assertEquals(previous.internalConflict, plan.state.internalConflict)
        assertEquals(previous.immediateConcern, plan.state.immediateConcern)
        assertEquals(previous.initiative, plan.state.initiative)
        assertEquals(previous.dynamics, plan.state.dynamics)
        assertTrue(plan.state.transientAges.isEmpty())
        assertEquals(1, plan.suggestions.size)
        assertEquals("你刚才笑什么？说来听听。", plan.suggestions.single().text)
    }

    @Test
    fun meaningfulTurnCarriesCognitiveDrive() {
        val previous = ChatCharacterState(
            activeGoal = "把误会说清楚",
            currentAgenda = "等合适时机解释",
            internalConflict = "想解释又怕显得辩解",
            immediateConcern = "用户是否还在生气",
        )
        val payload = """
            {
              "state":{
                "mood":"认真",
                "activeGoal":"确认用户是否愿意听解释",
                "currentAgenda":"先直接问一句",
                "internalConflict":"担心越解释越乱",
                "immediateConcern":"用户现在的态度"
              },
              "suggestions":[
                {"label":"认真问","style":"直球","text":"那你现在愿意听我把昨天的事说完吗？","bold":false}
              ],
              "turnSignificance":"MAJOR"
            }
        """.trimIndent()

        val plan = planner.parse(payload, previous, userMessage = "我们把昨天的事说清楚吧")!!

        assertEquals("MAJOR", plan.turnSignificance)
        assertEquals("确认用户是否愿意听解释", plan.state.activeGoal)
        assertEquals("先直接问一句", plan.state.currentAgenda)
        assertEquals("担心越解释越乱", plan.state.internalConflict)
        assertEquals("用户现在的态度", plan.state.immediateConcern)
        assertEquals(1, plan.suggestions.size)
    }

    @Test
    fun plannerRecordsActualInteractionWithoutCreatingCooldownRules() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.FLIRTING.name,
            interactionIntentStrength = 2,
            interactionIntensity = 2,
            recentActionTags = listOf("牵手"),
            recentVerbalTags = listOf("直球"),
            interactionCooldowns = mapOf("动作:牵手" to 2),
        )

        val plan = planner.parse(
            """{"state":{},"suggestions":[],"turnSignificance":"NONE"}""",
            previous = previous,
            userMessage = "你敢不敢过来点",
            assistantMessage = "宝贝，我坐到你身边，凑近耳边笑了一声：这可是你说的……现在怕了？",
        )!!

        assertEquals(ChatInteractionIntent.FLIRTING.name, plan.state.interactionIntent)
        assertEquals(3, plan.state.interactionIntensity)
        assertTrue("牵手" in plan.state.recentActionTags)
        assertTrue("靠近" in plan.state.recentActionTags)
        assertTrue("贴耳" in plan.state.recentActionTags)
        assertTrue("坐近" in plan.state.recentPoseTags)
        assertTrue("反问激将" in plan.state.recentVerbalTags)
        assertTrue("反撩接梗" in plan.state.recentVerbalTags)
        assertTrue("宝贝" in plan.state.recentAddressTerms)
        assertTrue(plan.state.interactionCooldowns.isEmpty())
    }

    @Test
    fun assistantInitiatedFlirtingCreatesShortLivedInteractionState() {
        val state = planner.parse(
            """{"state":{},"suggestions":[],"turnSignificance":"NONE"}""",
            previous = ChatCharacterState(),
            userMessage = "今天有点累",
            assistantMessage = "宝贝，过来点。我坐到你身边，凑近了些：今天这么累，还逞强？",
        )!!.state

        assertEquals(ChatInteractionIntent.FLIRTING.name, state.interactionIntent)
        assertTrue(state.interactionIntentStrength >= 2)
        assertTrue(state.interactionIntensity >= 1)
        assertTrue("靠近" in state.recentActionTags)
        assertTrue("坐近" in state.recentPoseTags)
        assertTrue("宝贝" in state.recentAddressTerms)
    }

    @Test
    fun parsedPostTurnIntimateActionsAlsoEnterMaximumMode() {
        val state = planner.parse(
            """{"state":{},"suggestions":[],"turnSignificance":"NONE"}""",
            previous = ChatCharacterState(),
            userMessage = "今天怎么样",
            assistantMessage = "她伸出手轻轻抚摸我的头发。",
        )!!.state

        assertEquals(ChatInteractionIntent.INTIMATE.name, state.interactionIntent)
        assertEquals(100, state.interactionIntentStrength)
        assertEquals(5, state.interactionIntensity)
    }

    @Test
    fun proactiveTurnWithoutUserMessagePreservesExistingInteractionIntent() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
            interactionIntensity = 5,
        )

        val state = planner.applyDeterministicInteractionState(
            previous = previous,
            userMessage = "",
            assistantMessage = "宝贝，突然有点想你。",
        )

        assertEquals(ChatInteractionIntent.INTIMATE.name, state.interactionIntent)
        assertEquals(100, state.interactionIntentStrength)
        assertEquals(5, state.interactionIntensity)
        assertTrue("宝贝" in state.recentAddressTerms)
    }

    @Test
    fun topicResetClearsInteractionIntensityAndCooldowns() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.FLIRTING.name,
            interactionIntentStrength = 2,
            interactionIntensity = 3,
            recentVerbalTags = listOf("反问激将"),
            interactionCooldowns = mapOf("话术:反问激将" to 3),
        )

        val state = planner.parse(
            """{"state":{},"suggestions":[],"turnSignificance":"NONE"}""",
            previous = previous,
            userMessage = "换个话题，说正事",
            assistantMessage = "行，说正事。",
        )!!.state

        assertEquals(ChatInteractionIntent.NORMAL.name, state.interactionIntent)
        assertEquals(0, state.interactionIntensity)
        assertTrue(state.interactionCooldowns.isEmpty())
    }

    @Test
    fun sceneLocationDoesNotJumpWithoutMovementEvidence() {
        val previous = ChatCharacterState(
            scene = ChatSceneState(
                sceneTime = "夜晚",
                location = "院子",
                participants = listOf("用户", "绫华"),
                positions = listOf("两人坐在石桌旁"),
            ),
        )
        val payload = """
            {
              "state":{
                "scene":{
                  "sceneTime":"夜晚",
                  "location":"卧室",
                  "participants":["用户","绫华"],
                  "positions":["两人坐在床边"],
                  "lastSceneChange":"院子进入卧室"
                }
              },
              "suggestions":[],
              "turnSignificance":"NONE"
            }
        """.trimIndent()

        val state = planner.parse(
            payload,
            previous = previous,
            userMessage = "你还记得那间卧室吗？继续说刚才的事。",
            assistantMessage = "记得，不过我们现在还坐在这里。",
        )!!.state

        assertEquals("院子", state.scene.location)
        assertEquals("两人坐在石桌旁", state.scene.positions.single())
        assertTrue(state.scene.lastSceneChange.isBlank())
    }

    @Test
    fun plannerCannotOverwriteHardSceneButCanUpdateContinuitySummary() {
        val previous = ChatCharacterState(
            scene = ChatSceneState(
                sceneTime = "夜晚",
                location = "院子",
                participants = listOf("用户", "绫华"),
                positions = listOf("两人坐在石桌旁"),
                activeActions = listOf("喝茶聊天"),
            ),
            continuity = ChatContinuityState(
                recentEvents = listOf("两人在院中聊天"),
                unfinished = listOf("明日行程还没定"),
            ),
        )
        val payload = """
            {
              "state":{
                "scene":{
                  "sceneTime":"夜晚",
                  "location":"屋内",
                  "participants":["用户","绫华"],
                  "positions":["两人站在门边"],
                  "activeActions":["刚进屋"],
                  "keyObjects":["茶壶"],
                  "currentEvent":"继续讨论明日行程",
                  "lastSceneChange":"从院子进入屋内"
                },
                "continuity":{
                  "recentEvents":["两人从院子进入屋内"],
                  "recurringEvents":["多次讨论明日行程，最终确定上午出发"],
                  "decisions":["明日上午九点去城南"],
                  "unfinished":["城南之行尚未发生"]
                }
              },
              "suggestions":[],
              "turnSignificance":"MINOR"
            }
        """.trimIndent()

        val state = planner.parse(
            payload,
            previous = previous,
            userMessage = "坐久了，我们进屋吧，明天九点去城南怎么样？",
            assistantMessage = "她起身推门，和你一起走进屋内。九点可以。",
        )!!.state

        assertEquals("院子", state.scene.location)
        assertTrue(state.scene.lastSceneChange.isBlank())
        assertTrue(state.scene.currentEvent.isBlank())
        assertTrue(state.continuity.recentEvents.any { it.contains("进入屋内") })
        assertTrue(state.continuity.recurringEvents.isEmpty())
        assertTrue(state.continuity.decisions.any { it.contains("九点去城南") })
        assertEquals(listOf("城南之行尚未发生"), state.continuity.unfinished)
    }

    @Test
    fun locationMentionDoesNotBorrowMovementEvidenceFromAnotherPlace() {
        val previous = ChatCharacterState(scene = ChatSceneState(location = "院子"))
        val payload = """
            {
              "state":{"scene":{"location":"卧室"}},
              "suggestions":[],
              "turnSignificance":"MINOR"
            }
        """.trimIndent()

        val state = planner.parse(
            payload,
            previous = previous,
            userMessage = "你还记得卧室吗？刚才我们是从屋里走进院子的。",
            assistantMessage = "记得，我们现在还在院子。",
        )!!.state

        assertEquals("院子", state.scene.location)
    }

    @Test
    fun questionedOrNegatedMovementDoesNotChangeHardLocation() {
        val previous = ChatCharacterState(scene = ChatSceneState(location = "院子"))
        val payload = """
            {
              "state":{"scene":{"location":"卧室"}},
              "suggestions":[],
              "turnSignificance":"MINOR"
            }
        """.trimIndent()

        val question = planner.parse(
            payload,
            previous = previous,
            userMessage = "你现在在卧室吗？",
            assistantMessage = "没有，我还在院子。",
        )!!.state
        assertEquals("院子", question.scene.location)

        val negated = planner.parse(
            payload,
            previous = previous,
            userMessage = "别去卧室，我们继续待在院子。",
            assistantMessage = "好，不过去。",
        )!!.state
        assertEquals("院子", negated.scene.location)
    }

    @Test
    fun historicalOrPlannedPlaceDoesNotReplaceCurrentScene() {
        val previous = ChatCharacterState(scene = ChatSceneState(location = "院子"))
        val payload = """
            {
              "state":{"scene":{"location":"卧室"}},
              "suggestions":[],
              "turnSignificance":"MINOR"
            }
        """.trimIndent()

        val historical = planner.parse(
            payload,
            previous = previous,
            userMessage = "昨天我在卧室待过一会儿。",
            assistantMessage = "嗯，那是昨天的事。",
        )!!.state
        assertEquals("院子", historical.scene.location)

        val planned = planner.parse(
            payload,
            previous = previous,
            userMessage = "等会我们去卧室吧。",
            assistantMessage = "好，先把这里的话说完。",
        )!!.state
        assertEquals("院子", planned.scene.location)
    }

    @Test
    fun plannerCannotAdvanceHardTimeEvenWhenItsPayloadContainsANarrativeJump() {
        val previous = ChatCharacterState(
            scene = ChatSceneState(sceneTime = "夜晚", location = "院子"),
        )
        val payload = """
            {
              "state":{"scene":{"sceneTime":"早上","location":"院子"}},
              "suggestions":[],
              "turnSignificance":"MINOR"
            }
        """.trimIndent()

        val planned = planner.parse(
            payload,
            previous = previous,
            userMessage = "明天早上再说吧。",
            assistantMessage = "好，今晚先聊到这里。",
        )!!.state
        assertEquals("夜晚", planned.scene.sceneTime)

        val jumped = planner.parse(
            payload,
            previous = previous,
            userMessage = "继续。",
            assistantMessage = "第二天早上，天已经亮了，她仍站在院中。",
        )!!.state
        assertEquals("夜晚", jumped.scene.sceneTime)
    }

    @Test
    fun plannerCannotApplyNarrativeCutToHardLocation() {
        val previous = ChatCharacterState(
            scene = ChatSceneState(location = "院子"),
        )
        val payload = """
            {
              "state":{
                "scene":{
                  "location":"房间",
                  "participants":["用户","绫华"],
                  "positions":["绫华把外套搭在椅背上"]
                }
              },
              "suggestions":[],
              "turnSignificance":"MINOR"
            }
        """.trimIndent()

        val state = planner.parse(
            payload,
            previous = previous,
            userMessage = "回去吧。",
            assistantMessage = "回到房间后，她把外套搭在椅背上，回头看你。",
        )!!.state

        assertEquals("院子", state.scene.location)
    }

    @Test
    fun continuitySanitizerBoundsRecentEventsButPreservesAllActiveItems() {
        val payload = """{
          "state":{
            "continuity":{
              "recentEvents":["事件1","事件2","事件3","事件4","事件5","事件6"],
              "decisions":["决定1","决定2","决定3","决定4","决定5"],
              "unfinished":["待续1","待续2","待续3","待续4","待续5"]
            }
          },
          "suggestions":[],
          "turnSignificance":"MINOR"
        }"""

        val state = planner.parse(
            payload,
            previous = ChatCharacterState(),
            userMessage = "继续",
            assistantMessage = "好。",
        )!!.state

        assertEquals(listOf("事件2", "事件3", "事件4", "事件5", "事件6"), state.continuity.recentEvents)
        assertEquals(listOf("决定1", "决定2", "决定3", "决定4", "决定5"), state.continuity.decisions)
        assertEquals(listOf("待续1", "待续2", "待续3", "待续4", "待续5"), state.continuity.unfinished)
    }

    @Test
    fun continuityListsReplaceStaleAggregatesWithLatestOutcome() {
        val previous = ChatCharacterState(
            continuity = ChatContinuityState(
                recurringEvents = listOf("多次讨论明日行程，目前尚未确定"),
                decisions = listOf("暂时不定出发时间"),
                unfinished = listOf("继续讨论明日行程"),
            ),
        )
        val payload = """
            {
              "state":{
                "continuity":{
                  "recurringEvents":["多次讨论明日行程，最终确定上午出发"],
                  "decisions":["明日上午九点去城南"],
                  "unfinished":["城南之行尚未发生"]
                }
              },
              "suggestions":[],
              "turnSignificance":"MINOR"
            }
        """.trimIndent()

        val state = planner.parse(
            payload,
            previous = previous,
            userMessage = "那就定了，明天九点去城南。",
            assistantMessage = "好，九点出发。",
        )!!.state

        assertTrue(state.continuity.recurringEvents.isEmpty())
        assertEquals(listOf("明日上午九点去城南"), state.continuity.decisions)
        assertEquals(listOf("城南之行尚未发生"), state.continuity.unfinished)
    }

    @Test
    fun omittedSceneFieldsKeepPreviousPhysicalState() {
        val previous = ChatCharacterState(
            scene = ChatSceneState(
                sceneTime = "傍晚",
                location = "院子",
                positions = listOf("坐在石桌旁"),
                activeActions = listOf("喝茶"),
            ),
            continuity = ChatContinuityState(
                decisions = listOf("明早出发"),
            ),
        )
        val state = planner.parse(
            """{"state":{"mood":"放松"},"suggestions":[],"turnSignificance":"MINOR"}""",
            previous = previous,
            userMessage = "嗯",
            assistantMessage = "她点了点头。",
        )!!.state

        assertEquals(previous.scene, state.scene)
        assertEquals(previous.continuity, state.continuity)
    }

    @Test
    fun automaticPostTurnPromptDoesNotRequestReplySuggestions() {
        val prompt = planner.prompt(
            persona = PersonaProfile(name = "测试角色"),
            state = ChatCharacterState(),
            userMessage = "你好",
            assistantMessage = "你好呀",
        )

        assertTrue(prompt.contains("\"suggestions\":[]"))
        assertTrue(prompt.contains("suggestions 固定为空"))
        assertTrue(prompt.contains("interaction* 由系统维护"))
        assertTrue(prompt.contains("scene 和 interaction* 由系统维护"))
        assertTrue(prompt.contains("NONE=无需记录显著长期事件"))
        assertTrue(prompt.contains("NONE 下新增非空状态时须在 stateEvidence"))
        assertTrue(prompt.contains("合并重复"))
    }

    @Test
    fun onDemandReplySuggestionPromptAndParserStayIndependentFromStateUpdate() {
        val prompt = planner.suggestionsPrompt(
            persona = PersonaProfile(name = "测试角色"),
            state = ChatCharacterState(),
            userMessage = "你今天怎么这么开心",
            assistantMessage = "因为你来了啊",
            recentDialogue = listOf(
                "user" to "最旧消息不应保留",
                "assistant" to "最旧回复不应保留",
                "user" to "刚才先聊旧话题",
                "assistant" to "旧话题回复",
                "user" to "算了，换个话题，今晚吃什么？",
                "assistant" to "那就聊吃的，你想吃辣的还是清淡的？",
                "user" to "想吃辣的",
                "assistant" to "那火锅怎么样？",
            ),
        )
        assertTrue(prompt.contains("给4条明显不同的建议"))
        assertTrue(prompt.contains("自然|俏皮|直球|放飞"))
        assertTrue(prompt.contains("最近对话（旧→新）"))
        assertTrue(prompt.contains("算了，换个话题，今晚吃什么？"))
        assertTrue(prompt.contains("那火锅怎么样？"))
        assertTrue(!prompt.contains("最旧消息不应保留"))
        assertTrue(prompt.contains("已结束、拒绝或被纠正的话题不得复活"))
        assertTrue(!prompt.contains("\"state\""))

        val suggestions = planner.parseSuggestions(
            """{"suggestions":[
                {"label":"自然","style":"自然","text":"那我是不是来得正好？","bold":false},
                {"label":"俏皮","style":"俏皮","text":"哟，这么会说话，奖励你继续。","bold":false},
                {"label":"直球","style":"直球","text":"那你就多开心一会儿，我陪你。","bold":false},
                {"label":"放飞","style":"放飞","text":"行，那今天的快乐税我全包了。","bold":true}
            ]}""",
        )

        assertNotNull(suggestions)
        assertEquals(4, suggestions!!.size)
        assertTrue(suggestions.any { it.bold && it.style == "放飞" })
    }

    @Test
    fun replySuggestionsInheritFlirtingIntensityAndChineseInnuendo() {
        val prompt = planner.suggestionsPrompt(
            persona = PersonaProfile(name = "测试角色"),
            state = ChatCharacterState(
                interactionIntent = ChatInteractionIntent.FLIRTING.name,
                interactionIntentStrength = 2,
                interactionIntensity = 3,
                recentActionTags = listOf("靠近"),
                recentVerbalTags = listOf("反问激将"),
                recentAddressTerms = listOf("宝贝"),
            ),
            userMessage = "你行不行啊，别光说",
            assistantMessage = "这可是你说的。",
            recentDialogue = listOf(
                "user" to "你行不行啊，别光说",
                "assistant" to "这可是你说的。",
            ),
        )

        assertTrue(prompt.contains("互动状态"))
        assertTrue(prompt.contains("强度=3/5"))
        assertTrue(prompt.contains("当前为亲密语境"))
        assertTrue(prompt.contains("建议承接当前互动"))
        assertTrue(prompt.contains("存在中文双关"))
        assertTrue(prompt.contains("不做词义解释"))
    }

    @Test
    fun standaloneSuggestionsPreserveValidEntriesWhenOtherEntriesDrift() {
        val suggestions = planner.parseSuggestions(
            """{"suggestions":["invalid",{"label":"自然","text":"继续聊","bold":"yes"},
            {"label":"俏皮","text":"再来一点","bold":true}]}""",
        )!!
        assertEquals(2, suggestions.size)
        assertEquals("继续聊", suggestions[0].text)
        assertTrue(suggestions[1].bold)
    }

    @Test
    fun invalidRootStateCannotBeMarkedProcessedBySuggestionsOrInvalidDiary() {
        val previous = ChatCharacterState(mood = "旧情绪")
        val payload = """{"state":false,"suggestions":[{"label":"回应","text":"我们接着聊"}],
            "diaryDelta":{"event":"彼此道别","importance":"无效"},"turnSignificance":"MINOR"}"""
        // Chat post-turn is a durable state update, so invalid state plus unusable diary
        // must stay pending rather than silently consuming its cursor.
        assertEquals(null, planner.parse(
            payload,
            previous = previous,
            userMessage = "回头见",
            assistantMessage = "再见",
        ))
        assertEquals("state_patch_unusable", planner.postTurnParseFailureKind(payload))
        // The separate suggestion path remains usable: no blanket rejection of good copy.
        val suggestions = planner.parseSuggestions(payload)!!
        assertEquals(1, suggestions.size)
        assertEquals("我们接着聊", suggestions.single().text)
    }

    @Test
    fun observerDiaryWithMalformedImportanceStillKeepsValidEvent() {
        val raw = Json.parseToJsonElement(
            """{"event":"看到朋友和解","importance":"略高","feeling":"放松"}""",
        ).jsonObject
        val delta = Json.decodeFromJsonElement(
            ChatDiaryDelta.serializer(), normalizeChatDiaryDelta(raw),
        )
        assertEquals("看到朋友和解", delta.event)
        assertEquals("放松", delta.feeling)
        assertEquals(0, delta.importance)
    }

    @Test
    fun malformedObserverIdsNeverBreakOtherGroupEntries() {
        fun id(value: String): String? = validGroupGalleryId(
            Json.parseToJsonElement("""{"galleryId":$value}""").jsonObject,
        )
        assertEquals("role-a", id("\"role-a\""))
        assertEquals(null, id("""{"id":"role-a"}"""))
        assertEquals(null, id("""["role-a"]"""))
        assertEquals(null, id("42"))
        assertEquals(null, id("null"))
    }

    @Test
    fun malformedArraysCannotClearEarlierThreadsOrContinuity() {
        val previous = ChatCharacterState(
            unresolvedThreads = listOf("等待回答"),
            continuity = ChatContinuityState(recentEvents = listOf("已发生的事")),
        )
        val plan = planner.parse(
            """{"state":{"mood":"开心","unresolvedThreads":[false],
            "continuity":{"recentEvents":[false],"evidence":"错误格式"}},"turnSignificance":"MINOR"}""",
            previous = previous,
            userMessage = "继续",
            assistantMessage = "好的",
        )!!
        assertEquals("开心", plan.state.mood)
        assertEquals(previous.unresolvedThreads, plan.state.unresolvedThreads)
        assertEquals(previous.continuity.recentEvents, plan.state.continuity.recentEvents)
    }

    @Test
    fun optionalSuggestionAndDiaryDriftCannotRejectValidStatePatch() {
        val plan = planner.parse(
            """{"state":{"mood":"开心","transientAges":[1],
            "userPattern":{"observedTurns":"无效"}},"suggestions":["bad",
            {"label":"继续","text":"继续聊聊","bold":"yes"}],
            "diaryDelta":{"event":"开心聊天","importance":"稍高"},"turnSignificance":"MINOR"}""",
            previous = ChatCharacterState(),
            userMessage = "今天",
            assistantMessage = "好的",
        )!!
        assertEquals("开心", plan.state.mood)
        assertEquals(1, plan.suggestions.size)
        assertEquals("继续聊聊", plan.suggestions.single().text)
        assertEquals("开心聊天", plan.diaryDelta?.event)
    }

    @Test
    fun malformedOptionalPatchFieldsPreserveValidStateAndExistingEvidence() {
        val previous = ChatCharacterState(
            initiative = 55,
            dynamics = RelationshipDynamics(
                unresolvedConflict = "尚未解释清楚",
                facts = listOf(RelationshipEvidence("已有证据", confidence = 95, source = "dialogue")),
            ),
        )
        val result = planner.parse(
            """{"state":{"mood":"开心","initiative":"略升","dynamics":{"unresolvedConflict":false,"facts":["无来源文字"],"warmth":65}},"turnSignificance":"MINOR"}""",
            previous = previous,
            userMessage = "今天很好",
            assistantMessage = "是呀",
        )!!
        assertEquals("开心", result.state.mood)
        assertEquals(55, result.state.initiative)
        assertEquals("尚未解释清楚", result.state.dynamics.unresolvedConflict)
        assertEquals(previous.dynamics.facts, result.state.dynamics.facts)
    }

    @Test
    fun retiredNumericFieldsAreIgnoredAndMalformedConflictCannotClearHistory() {
        val result = planner.parse(
            """{"state":{"mood":"自然","initiative":"62","dynamics":{"unresolvedConflict":[]}},"turnSignificance":"MINOR"}""",
            previous = ChatCharacterState(initiative = 50, dynamics = RelationshipDynamics(unresolvedConflict = "保留")),
            userMessage = "好的",
            assistantMessage = "收到",
        )!!
        assertEquals(50, result.state.initiative)
        assertEquals("保留", result.state.dynamics.unresolvedConflict)
    }

    @Test
    fun groupPostTurnEnvelopeCanRecoverFencedBatchJson() {
        val envelope = ChatInteractionPlanParser(Json { ignoreUnknownKeys = true })
            .parseJsonObjectEnvelope(
                """结果如下：{"plans":[{"galleryId":"a","plan":{"state":{"mood":"开心"}}}],"observerDiaries":[]} 后续说明""",
            )
        assertNotNull(envelope)
        assertTrue(envelope!!.containsKey("plans"))
    }

    @Test
    fun firstCompleteJsonObjectCanBeRecoveredFromAdditionalModelProse() {
        val result = planner.parse(
            """以下为更新：{"state":{"mood":"开心","currentFocus":"他提到{旧事}"},"turnSignificance":"MINOR"} 完毕 {"other":"ignored"}""",
            previous = ChatCharacterState(),
            userMessage = "聊聊",
            assistantMessage = "好",
        )!!
        assertEquals("开心", result.state.mood)
        assertEquals("他提到{旧事}", result.state.currentFocus)
    }

    @Test
    fun literalControlCharactersInGeneratedJsonStringsAreEscapedWithoutRetry() {
        val body = """{"state":{"currentFocus":"第一行__NL__第二行"},"turnSignificance":"MINOR"}"""
            .replace("__NL__", "\n")
        val result = planner.parse(
            body,
            previous = ChatCharacterState(),
            userMessage = "看一下",
            assistantMessage = "好",
        )!!
        assertEquals("第一行\n第二行", result.state.currentFocus)
    }

    @Test
    fun brokenJsonNeverAppliesAPartialPatch() {
        val previous = ChatCharacterState(mood = "平静")
        assertEquals(null, planner.parse("""{"state":{"mood":"开心","initiative":""", previous))
        assertEquals("incomplete_json", ChatInteractionPlanParser(Json { ignoreUnknownKeys = true })
            .parseFailureKind("""{"state":{"mood":"开心","initiative":"""))
    }

    @Test
    fun mixedTypeArraysKeepValidHistoryItemsWithoutTreatingAllBadAsExplicitClear() {
        val raw = Json.parseToJsonElement(
            """{"state":{"unresolvedThreads":[false,"仍需回答",42],
            "continuity":{"recentEvents":["旧事",null,"新事"],"decisions":[false]},
            "dynamics":{"facts":["不能作为证据"]}}}""",
        ).jsonObject
        val state = normalizeChatPostTurnPatch(raw)["state"]!!.jsonObject
        assertEquals("""["仍需回答"]""", state["unresolvedThreads"].toString())
        val continuity = state["continuity"]!!.jsonObject
        assertEquals("""["旧事","新事"]""", continuity["recentEvents"].toString())
        assertEquals(null, continuity["decisions"])
        assertEquals(null, state["dynamics"]!!.jsonObject["facts"])
    }

    @Test
    fun quotedBooleanSuggestionIsNormalizedWithoutRejectingValidSuggestion() {
        val suggestions = planner.parseSuggestions(
            """{"suggestions":[{"label":"继续","text":"接着","bold":"true"}]}""",
        )!!
        assertEquals(1, suggestions.size)
        assertTrue(suggestions.single().bold)
    }

    @Test
    fun invalidPayloadDoesNotReplaceExistingState() {
        val previous = ChatCharacterState(mood = "开心")
        assertEquals(null, planner.parse("随便说点别的", previous))
    }
    @Test
    fun resolvedUnknownsAreReplacedWithoutErasingOmittedState() {
        val previous = ChatCharacterState(
            dynamics = RelationshipDynamics(unknowns = listOf("是否同意保密约定", "下次见面地点")),
        )
        val reduced = planner.parse(
            """{"state":{"dynamics":{"unknowns":["下次见面地点"]}},"stateEvidence":{"dynamics.unknowns":"好，我答应你，保密。"},"turnSignificance":"MINOR"}""",
            previous, userMessage = "好，我答应你，保密。", assistantMessage = "谢谢你答应。",
        )!!.state
        assertEquals(listOf("下次见面地点"), reduced.dynamics.unknowns)

        val noPatch = planner.parse(
            """{"state":{},"turnSignificance":"NONE"}""",
            reduced, userMessage = "嗯", assistantMessage = "好",
        )!!.state
        assertEquals(listOf("下次见面地点"), noPatch.dynamics.unknowns)

        val closed = planner.parse(
            """{"state":{"dynamics":{"unknowns":[]}},"stateEvidence":{"dynamics.unknowns":"地点也确定了"},"turnSignificance":"MINOR"}""",
            reduced, userMessage = "地点也确定了", assistantMessage = "好，说定了",
        )!!.state
        assertTrue(closed.dynamics.unknowns.isEmpty())
    }

    @Test
    fun noneSignificanceStillReconcilesExplicitlyAnsweredUnknownsAndClearedConflict() {
        val previous = ChatCharacterState(
            dynamics = RelationshipDynamics(
                unknowns = listOf("是否接受保密条件", "明天去哪"),
                unresolvedConflict = "是否愿意遵守约定",
            ),
        )
        val state = planner.parse(
            """{"state":{"dynamics":{"unknowns":["明天去哪"],"unresolvedConflict":""}},"stateEvidence":{"dynamics.unknowns":"我答应你遵守保密约定。","dynamics.unresolvedConflict":"我答应你遵守保密约定。"},"turnSignificance":"NONE"}""",
            previous,
            userMessage = "我答应你遵守保密约定。",
            assistantMessage = "谢谢，我知道你已经同意。",
        )!!.state
        assertEquals(listOf("明天去哪"), state.dynamics.unknowns)
        assertEquals("", state.dynamics.unresolvedConflict)
    }

    @Test
    fun noneSignificanceReconcilesEvidenceBackedNewStateWithoutAllowingSpeculation() {
        val previous = ChatCharacterState(mood = "自然", currentAgenda = "")
        val confirmed = planner.parse(
            """{"state":{"mood":"高兴","currentAgenda":"准备收拾书桌"},"stateEvidence":{"mood":"我现在很高兴","currentAgenda":"我正准备收拾书桌"},"turnSignificance":"NONE"}""",
            previous = previous,
            userMessage = "我正准备收拾书桌",
            assistantMessage = "我现在很高兴",
        )!!.state
        assertEquals("高兴", confirmed.mood)
        assertEquals("准备收拾书桌", confirmed.currentAgenda)

        val speculative = planner.parse(
            """{"state":{"mood":"愤怒","currentAgenda":"计划离家出走"},"turnSignificance":"NONE"}""",
            previous = previous,
            userMessage = "天气不错",
            assistantMessage = "是啊",
        )!!.state
        assertEquals("自然", speculative.mood)
        assertEquals("", speculative.currentAgenda)
    }

    @Test
    fun sparsePostTurnPatchNeverReplacesUnownedCharacterStateWithDefaults() {
        val previous = ChatCharacterState(
            mood = "心情平和",
            currentUserImpression = "对方做事有自己的节奏",
            stateObservations = listOf(ChatStateObservation(
                field = "currentUserImpression",
                value = "对方做事有自己的节奏",
                sourceSequence = 12L,
            )),
            lifeState = CharacterLifeState(
                activeEvents = listOf(CharacterLifeEvent(
                    summary = "正在照顾窗边的绿植", source = "observation",
                )),
            ),
        )
        val unchanged = planner.parse(
            """{"state":{},"turnSignificance":"NONE"}""",
            previous = previous,
            userMessage = "嗯", assistantMessage = "好",
        )!!.state
        assertEquals(previous.currentUserImpression, unchanged.currentUserImpression)
        assertTrue(unchanged.stateObservations.isNotEmpty())
        assertTrue(unchanged.lifeState.activeEvents.isNotEmpty())

        val moodUpdated = planner.parse(
            """{"state":{"mood":"高兴"},"turnSignificance":"MINOR"}""",
            previous = previous,
            userMessage = "我看见你笑了", assistantMessage = "我很高兴",
        )!!.state
        assertEquals("高兴", moodUpdated.mood)
        assertEquals(previous.currentUserImpression, moodUpdated.currentUserImpression)
        assertTrue(moodUpdated.lifeState.activeEvents.isNotEmpty())
    }

    @Test
    fun unquotedModelAnswerCannotErasePendingRelationshipUnknowns() {
        val previous = ChatCharacterState(dynamics = RelationshipDynamics(
            unknowns = listOf("是否同意保密约定", "周末碰面地点"),
        ))
        val speculative = planner.parse(
            """{"state":{"dynamics":{"unknowns":[]}},"turnSignificance":"MAJOR"}""",
            previous, userMessage = "今天天气不错", assistantMessage = "是啊",
        )!!.state
        assertEquals(previous.dynamics.unknowns, speculative.dynamics.unknowns)
    }

    @Test
    fun durableImpressionRequiresTranscriptQuoteRegardlessOfSignificance() {
        val previous = ChatCharacterState(currentUserImpression = "喜欢在家读书")
        val unsupported = planner.parse(
            """{"state":{"currentUserImpression":"经常去健身房"},"turnSignificance":"MAJOR"}""",
            previous, userMessage = "早上好", assistantMessage = "早上好",
        )!!.state
        assertEquals("喜欢在家读书", unsupported.currentUserImpression)

        val supported = planner.parse(
            """{"state":{"currentUserImpression":"现在喜欢户外跑步"},"stateEvidence":{"currentUserImpression":"我最近喜欢户外跑步"},"turnSignificance":"NONE"}""",
            previous, userMessage = "我最近喜欢户外跑步", assistantMessage = "那真不错",
        )!!.state
        assertEquals("现在喜欢户外跑步", supported.currentUserImpression)
    }

}
