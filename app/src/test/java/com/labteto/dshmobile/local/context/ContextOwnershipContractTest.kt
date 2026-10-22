package com.labteto.dshmobile.local.context

import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalPromptPressure as LegacyModelPromptPressure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextOwnershipContractTest {
    @Test fun oldModelModeNamesRemainCompatibleWithSharedContextContract() {
        assertEquals(LocalContextCheckpointKind.WORK, LocalHistorySummaryMode.WORK.checkpointKind())
        assertEquals(LocalContextCheckpointKind.CHAT, LocalHistorySummaryMode.CHAT.checkpointKind())
        val request = buildTrustedContextCheckpointModelMessage(LocalHistorySummaryMode.WORK, "历史")
        assertTrue(isTrustedContextCheckpointModelMessage(request, LocalContextCheckpointKind.WORK))
        assertFalse(isTrustedContextCheckpointModelMessage(request, LocalContextCheckpointKind.CHAT))
    }

    @Test fun legacyWorkV1CheckpointRemainsTrusted() {
        val legacy = buildJsonObject {
            put("role", "user")
            put("_dsh_work_checkpoint_source", "history_compactor_v1")
            put("content", "旧版工作上下文")
        }
        assertTrue(isTrustedContextCheckpointModelMessage(legacy, LocalContextCheckpointKind.WORK))
        assertFalse(isTrustedContextCheckpointModelMessage(legacy, LocalContextCheckpointKind.CHAT))
    }

    @Test fun priorModelPressureNameResolvesToNeutralPressureValue() {
        val reading: LegacyModelPromptPressure = LocalPromptPressure(
            contextChars = 200, estimatedInputTokens = 100,
            systemTokens = 20, historyTokens = 30, currentUserTokens = 40,
            toolDefinitionTokens = 10, operationalLimitTokens = 200,
        )
        assertEquals(100, reading.remainingOperationalTokens)
        assertEquals(0.5, reading.usageRatio, 0.0001)
    }
}
