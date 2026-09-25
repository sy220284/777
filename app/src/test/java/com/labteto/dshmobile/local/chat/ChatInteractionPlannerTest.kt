package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInteractionPlannerTest {
    private val planner = ChatInteractionPlanner(Json { ignoreUnknownKeys = true })

    @Test fun parsesAndBoundsStateAndSuggestions() {
        val previous = ChatCharacterState(mood = "平静", relationshipState = "熟悉")
        val payload = """
            ```json
            {"state":{"mood":"有点不爽","relationshipState":"更熟了一点","currentFocus":"用户昨天失约","recentImpression":"嘴硬","unresolvedThreads":["昨天为什么没来","昨天为什么没来","吃饭"],"initiative":120,"shareDesire":-3},"suggestions":[
              {"label":"嘴硬","text":"谁说我是来找你的？"},
              {"label":"温柔","text":"好啦，我回来了。"},
              {"label":"追问","text":"你真一直在等我？"},
              {"label":"重复","text":"你真一直在等我？"},
              {"label":"多余","text":"第五条不会保留"}
            ]}
            ```
        """.trimIndent()

        val result = planner.parse(payload, previous)
        assertNotNull(result)
        val plan = result!!
        assertEquals("有点不爽", plan.state.mood)
        assertEquals(100, plan.state.initiative)
        assertEquals(0, plan.state.shareDesire)
        assertEquals(2, plan.state.unresolvedThreads.size)
        assertEquals(4, plan.suggestions.size)
        assertEquals(plan.suggestions.map { it.text }.distinct().size, plan.suggestions.size)
        assertTrue(plan.state.updatedAt > 0L)
    }

    @Test fun extractsJsonEvenWhenModelAddsSurroundingNoise() {
        val previous = ChatCharacterState()
        val payload = "状态如下：\n" +
            "{\"state\":{\"mood\":\"平静\"},\"suggestions\":[{\"label\":\"接话\",\"text\":\"然后呢？\"}]}\n" +
            "就这些。"
        val result = planner.parse(payload, previous)
        assertNotNull(result)
        assertEquals("平静", result!!.state.mood)
        assertEquals("然后呢？", result.suggestions.single().text)
    }

    @Test fun invalidPayloadDoesNotReplaceExistingState() {
        val previous = ChatCharacterState(mood = "开心")
        assertEquals(null, planner.parse("随便说点别的", previous))
    }
}
