package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterEvolutionTest {

    @Test
    fun legacyStateWithoutEvolutionStillDecodesWithStableDefaults() {
        val state = Json { ignoreUnknownKeys = true }.decodeFromString(
            ChatCharacterState.serializer(),
            """{"mood":"平静","initiative":64,"shareDesire":58}""",
        )

        assertEquals("平静", state.mood)
        assertEquals(0, state.evolution.observationCount)
        assertEquals(50, state.evolution.initiativeBaseline)
        assertEquals(50, state.evolution.opennessBaseline)
        assertEquals(50, state.evolution.securityBaseline)
    }

    @Test
    fun oneMinorTurnCannotRewriteLongTermCharacter() {
        val previous = baselineState()
        val current = strongerState(previous)

        val evolution = evolveCharacterEvolution(previous, current, "MINOR")

        assertEquals(50, evolution.initiativeBaseline)
        assertEquals(50, evolution.opennessBaseline)
        assertEquals(50, evolution.securityBaseline)
        assertEquals(1, evolution.initiativeMomentum)
        assertEquals(1, evolution.opennessMomentum)
        assertEquals(1, evolution.securityMomentum)
    }

    @Test
    fun repeatedMinorSignalsGraduallyMoveLongTermBaseline() {
        var previous = baselineState()
        repeat(3) {
            val current = strongerState(previous)
            val evolution = evolveCharacterEvolution(previous, current, "MINOR")
            previous = current.copy(evolution = evolution)
        }

        assertEquals(51, previous.evolution.initiativeBaseline)
        assertEquals(51, previous.evolution.opennessBaseline)
        assertEquals(51, previous.evolution.securityBaseline)
        assertEquals(3, previous.evolution.observationCount)
    }

    @Test
    fun majorExperienceMovesOnlyABoundedStepAndRecordsMilestone() {
        val previous = baselineState().copy(
            evolution = CharacterEvolutionState(
                initiativeBaseline = 50,
                opennessBaseline = 50,
                securityBaseline = 50,
                observationCount = 4,
            ),
        )
        val current = strongerState(previous).copy(
            dynamics = strongerState(previous).dynamics.copy(
                sharedMoments = listOf("一起熬过一次很难的危机"),
            ),
        )

        val evolution = evolveCharacterEvolution(previous, current, "MAJOR")

        assertEquals(53, evolution.initiativeBaseline)
        assertEquals(53, evolution.opennessBaseline)
        assertEquals(53, evolution.securityBaseline)
        assertEquals("一起熬过一次很难的危机", evolution.lastMajorMilestone)
    }

    @Test
    fun noneSignificanceNeverChangesLongTermEvolution() {
        val previous = baselineState().copy(
            evolution = CharacterEvolutionState(
                initiativeBaseline = 61,
                opennessBaseline = 57,
                securityBaseline = 66,
                observationCount = 8,
                initiativeMomentum = 2,
                lastMajorMilestone = "已经共同处理过一次冲突",
            ),
        )

        assertEquals(
            previous.evolution,
            evolveCharacterEvolution(previous, strongerState(previous), "NONE"),
        )
    }

    @Test
    fun plannerCannotDirectlyOverwriteSystemOwnedEvolution() {
        val planner = ChatInteractionPlanner(Json { ignoreUnknownKeys = true })
        val previous = ChatCharacterState(
            initiative = 60,
            shareDesire = 60,
            dynamics = RelationshipDynamics(
                trust = 60,
                stability = 60,
                tension = 40,
            ),
            evolution = CharacterEvolutionState(
                initiativeBaseline = 60,
                opennessBaseline = 60,
                securityBaseline = 60,
                observationCount = 4,
            ),
        )
        val payload = """
            {
              "state":{
                "evolution":{
                  "initiativeBaseline":100,
                  "opennessBaseline":0,
                  "securityBaseline":100,
                  "observationCount":999
                }
              },
              "suggestions":[],
              "turnSignificance":"MINOR"
            }
        """.trimIndent()

        val state = planner.parse(
            payload,
            previous = previous,
            userMessage = "今天聊点轻松的",
            assistantMessage = "好啊。",
        )!!.state

        assertEquals(60, state.evolution.initiativeBaseline)
        assertEquals(60, state.evolution.opennessBaseline)
        assertEquals(60, state.evolution.securityBaseline)
        assertEquals(5, state.evolution.observationCount)
    }

    @Test
    fun evolutionContextMakesLongTermLayerDistinctFromImmediateState() {
        val evolution = CharacterEvolutionState(
            initiativeBaseline = 58,
            opennessBaseline = 63,
            securityBaseline = 71,
            observationCount = 6,
            lastMajorMilestone = "一起解决过一次重大误会",
        )
        val prompt = buildString { appendCharacterEvolutionContext(evolution) }

        assertTrue(prompt.contains("【长期人物演变】"))
        assertTrue(prompt.contains("长期主动=58/100"))
        assertTrue(prompt.contains("表达开放=63/100"))
        assertTrue(prompt.contains("关系安全感=71/100"))
        assertTrue(prompt.contains("基础人设、硬约束与用户纠正优先"))
    }

    @Test
    fun galleryMergePrefersTheMoreEstablishedEvolution() {
        val established = CharacterEvolutionState(
            initiativeBaseline = 62,
            opennessBaseline = 59,
            securityBaseline = 68,
            observationCount = 12,
            lastMajorMilestone = "一起经历过搬家",
        )
        val shallow = CharacterEvolutionState(
            initiativeBaseline = 90,
            opennessBaseline = 90,
            securityBaseline = 90,
            observationCount = 2,
        )

        assertEquals(established, mergeCharacterEvolution(established, shallow))
    }

    private fun baselineState(): ChatCharacterState = ChatCharacterState(
        initiative = 50,
        shareDesire = 50,
        dynamics = RelationshipDynamics(
            trust = 50,
            stability = 50,
            tension = 50,
        ),
    )

    private fun strongerState(previous: ChatCharacterState): ChatCharacterState = previous.copy(
        initiative = 80,
        shareDesire = 80,
        dynamics = previous.dynamics.copy(
            trust = 80,
            stability = 80,
            tension = 10,
        ),
    )
}
