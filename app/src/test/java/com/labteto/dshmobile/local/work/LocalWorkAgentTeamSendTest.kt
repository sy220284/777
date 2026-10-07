package com.labteto.dshmobile.local.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(prepared.content.contains("Agent 集群"))
        assertTrue(prepared.content.contains("Lead"))
        assertTrue(prepared.content.endsWith("审计当前仓库"))
        assertFalse(prepared.visibleContent.contains("team_spawn"))
        assertFalse(prepared.memoryInput.contains("Agent 集群"))
    }
}
