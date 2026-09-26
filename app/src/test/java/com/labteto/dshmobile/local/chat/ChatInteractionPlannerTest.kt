package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
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

    private val planner = ChatInteractionPlanner(Json { ignoreUnknownKeys = true })

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
    fun legacyReplyDraftStillWorksAndLegacyDirectionIsRetired() {
        val previous = ChatCharacterState(
            narrativeDirection = ChatNarrativeDirection("说开误会", "角色给彼此澄清的机会"),
        )
        val payload = """{"state":{"mood":"自然"},"suggestions":[{"label":"自然接话","text":"那你继续说，我听着。"}]}"""

        val plan = planner.parse(payload, previous)!!
        assertTrue(plan.state.narrativeDirection == null)
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
    fun factsHypothesesAndUnknownsStaySeparated() {
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
        assertEquals(1, plan.state.dynamics.hypotheses.size)
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
    fun noOpTurnPreservesCharacterStateButKeepsReplySuggestions() {
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
        assertEquals(previous, plan.state)
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
    fun invalidPayloadDoesNotReplaceExistingState() {
        val previous = ChatCharacterState(mood = "开心")
        assertEquals(null, planner.parse("随便说点别的", previous))
    }
}
