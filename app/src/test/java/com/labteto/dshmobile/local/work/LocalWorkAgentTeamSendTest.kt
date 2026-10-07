package com.labteto.dshmobile.local.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LocalWorkAgentTeamSendTest {
    @Test
    fun clusterModeAddsLeadDirectiveWithoutPollutingVisibleTextOrMemory() {
        val prepared = requireNotNull(
            prepareLocalAgentTeamSend(
                text = "审计当前仓库",
                attachments = emptyList(),
            ),
        )

        assertEquals("审计当前仓库", prepared.visibleContent)
        assertEquals("审计当前仓库", prepared.memoryInput)
        assertEquals("审计当前仓库", prepared.content)
        assertEquals("审计当前仓库", prepared.modelMessage["content"]!!.jsonPrimitive.content)
        val request = withLocalWorkExecutionMode(listOf(prepared.modelMessage))
        assertTrue(request.first()["content"]!!.jsonPrimitive.content.contains("Lead"))
        assertFalse(request.last().containsKey(LOCAL_WORK_EXECUTION_MODE_KEY))
        assertFalse(prepared.visibleContent.contains("team_spawn"))
        assertFalse(prepared.memoryInput.contains("Agent 集群"))
    }
    @Test
    fun ordinaryNextUserMessageExitsTeamModeWithoutChangingDurableHistory() {
        val team = requireNotNull(prepareLocalAgentTeamSend("并行审计", emptyList())).modelMessage
        val ordinary = buildJsonObject { put("role", "user"); put("content", "解释结果") }
        val history = listOf(team, ordinary)
        val request = withLocalWorkExecutionMode(history)
        assertEquals(2, request.size)
        assertEquals("并行审计", request.first()["content"]!!.jsonPrimitive.content)
        assertTrue(history.first().containsKey(LOCAL_WORK_EXECUTION_MODE_KEY))
        assertTrue(request.none { it.containsKey(LOCAL_WORK_EXECUTION_MODE_KEY) })
    }

}
