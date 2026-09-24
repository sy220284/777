package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInteractionPlannerTest {
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
                {"label":"嘴硬","text":"谁说我是来找你的？"},
                {"label":"温柔","text":"好啦，我回来了。"},
                {"label":"追问","text":"你真一直在等我？"},
                {"label":"重复","text":"你真一直在等我？"},
                {"label":"多余","text":"第五条不会保留"}
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
        assertTrue(plan.state.updatedAt > 0L)
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

        val plan = planner.parse(payload, previous)!!

        assertEquals(1, plan.state.dynamics.facts.size)
        assertEquals("对方明确说周末有空", plan.state.dynamics.facts.single().text)
        assertEquals(1, plan.state.dynamics.hypotheses.size)
        assertEquals(1, plan.state.dynamics.unknowns.size)
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
    fun invalidPayloadDoesNotReplaceExistingState() {
        val previous = ChatCharacterState(mood = "开心")
        assertEquals(null, planner.parse("随便说点别的", previous))
    }
}
