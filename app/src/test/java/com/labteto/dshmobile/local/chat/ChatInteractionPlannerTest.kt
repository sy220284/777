package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInteractionPlannerTest {

    @Test
    fun shortLivedStateExpiresWhenPlannerKeepsReturningNone() {
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

        assertTrue(state.currentFocus.isBlank())
        assertTrue(state.immediateConcern.isBlank())
        assertTrue(state.unresolvedThreads.isNotEmpty())
    }

    @Test
    fun topicResetImmediatelyDropsCurrentTopicAndOpenThreads() {
        val previous = ChatCharacterState(
            currentFocus = "旧话题",
            currentAgenda = "继续解释旧事",
            immediateConcern = "还在纠结",
            unresolvedThreads = listOf("旧线索"),
        )
        val state = planner.parse(
            """{"state":{},"suggestions":[],"turnSignificance":"NONE"}""",
            previous = previous,
            userMessage = "换个话题，说正事",
            assistantMessage = "好",
        )!!.state

        assertTrue(state.currentFocus.isBlank())
        assertTrue(state.currentAgenda.isBlank())
        assertTrue(state.immediateConcern.isBlank())
        assertTrue(state.unresolvedThreads.isEmpty())
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

    private val planner = ChatInteractionPlanner(Json { ignoreUnknownKeys = true })

    @Test
    fun plannerAndSuggestionsHonorCorrectionsWithoutFixedExpressionRecipe() {
        val persona = PersonaProfile(
            name = "阿青",
            portrait = "嘴硬心软",
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
        assertTrue(plannerPrompt.contains("角色台词的模糊或习惯性推辞不得单独触发关系降温"))
        assertTrue(suggestionsPrompt.contains("用户纠正（最高优先）"))
        assertTrue(suggestionsPrompt.contains("不把单个模糊推辞自动解释为关系拒绝"))
        assertTrue(suggestionsPrompt.contains("清晰明确停止、退出或拒绝继续一次即生效"))
    }

    @Test
    fun genericRestraintAloneDoesNotEnableIndirectRefusalSemantics() {
        val persona = PersonaProfile(
            name = "阿青",
            portrait = "克制谨慎，表达含蓄",
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
    fun explicitCurrentBoundaryIsOneShotAndDoesNotRequireRepetition() {
        val persona = PersonaProfile(
            name = "阿青",
            portrait = "嘴硬心软",
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

        assertTrue(plannerPrompt.contains("一次表达即生效，不要求重复"))
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
        assertEquals(65, plan.state.initiative)
        assertEquals(35, plan.state.shareDesire)
        assertEquals(60, plan.state.dynamics.warmth)
        assertEquals(58, plan.state.dynamics.trust)
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
    fun userPatternChangesGradually() {
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

        assertEquals("long", plan.state.userPattern.replyLength)
        assertEquals(50, plan.state.userPattern.directness)
        assertEquals(50, plan.state.userPattern.playfulness)
        assertEquals(50, plan.state.userPattern.initiative)
    }

    @Test
    fun observedMessageLengthGroundsReplyLengthProfile() {
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

        assertEquals("short", plan.state.userPattern.replyLength)
        assertEquals(4, plan.state.userPattern.observedTurns)
        assertTrue(plan.state.userPattern.averageMessageChars < 20)
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
    fun noOpTurnIgnoresSpuriousStateChangesButStillAgesTransientState() {
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
        assertTrue(plan.state.transientAges.values.all { it >= 1 })
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
    fun plannerPersistsActualInteractionPerformanceAndCooldowns() {
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
        assertEquals(1, plan.state.interactionCooldowns["动作:牵手"])
        assertEquals(3, plan.state.interactionCooldowns["动作:靠近"])
        assertEquals(3, plan.state.interactionCooldowns["话术:反问激将"])
        assertEquals(2, plan.state.interactionCooldowns["称呼:宝贝"])
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
    fun continuitySanitizerKeepsNewestEntriesWhenModelReturnsTooMany() {
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
        assertEquals(listOf("决定2", "决定3", "决定4", "决定5"), state.continuity.decisions)
        assertEquals(listOf("待续2", "待续3", "待续4", "待续5"), state.continuity.unfinished)
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
        assertTrue(prompt.contains("NONE=无有效变化"))
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
    fun wrongRootStateTypePreservesValidSuggestionsAndDiary() {
        val previous = ChatCharacterState(mood = "旧情绪")
        val plan = planner.parse(
            """{"state":false,"suggestions":[{"label":"回应","text":"我们接着聊"}],
            "diaryDelta":{"event":"彼此道别","importance":"无效"},"turnSignificance":"MINOR"}""",
            previous = previous,
            userMessage = "回头见",
            assistantMessage = "再见",
        )!!
        assertEquals("旧情绪", plan.state.mood)
        assertEquals(1, plan.suggestions.size)
        assertEquals("我们接着聊", plan.suggestions.single().text)
        assertEquals("彼此道别", plan.diaryDelta?.event)
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
    fun numericStringsCanBeNormalizedWithoutGuessingQualitativeConflict() {
        val result = planner.parse(
            """{"state":{"initiative":"62","dynamics":{"unresolvedConflict":[]}},"turnSignificance":"MINOR"}""",
            previous = ChatCharacterState(initiative = 50, dynamics = RelationshipDynamics(unresolvedConflict = "保留")),
            userMessage = "好的",
            assistantMessage = "收到",
        )!!
        assertEquals(62, result.state.initiative)
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
}
