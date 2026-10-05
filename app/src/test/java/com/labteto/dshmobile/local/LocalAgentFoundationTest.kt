package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.context.LocalContextCheckpointKind
import com.labteto.dshmobile.local.context.buildTrustedContextCheckpointModelMessage
import com.labteto.dshmobile.local.context.historySummaryMode
import com.labteto.dshmobile.local.context.isTrustedContextCheckpointModelMessage
import com.labteto.dshmobile.local.context.projectLocalRequestContext
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalPromptCachePolicy
import com.labteto.dshmobile.local.model.LocalPromptPressureMeter
import com.labteto.dshmobile.local.model.LocalRequestPressureStore
import com.labteto.dshmobile.local.model.LocalWorkCheckpoint
import com.labteto.dshmobile.local.runtime.structuredWorkState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentFoundationTest {
    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    @Test
    fun contextCheckpointEnvelopeSeparatesModesAndKeepsLegacyWorkReadable() {
        val chat = buildTrustedContextCheckpointModelMessage(LocalContextCheckpointKind.CHAT, "较早聊天")
        val work = buildTrustedContextCheckpointModelMessage(LocalContextCheckpointKind.WORK, "较早工作")
        val legacyWork = buildJsonObject {
            put("role", "user")
            put("_dsh_work_checkpoint_source", "history_compactor_v1")
            put("content", "旧工作检查点")
        }

        assertTrue(isTrustedContextCheckpointModelMessage(chat, LocalContextCheckpointKind.CHAT))
        assertFalse(isTrustedContextCheckpointModelMessage(chat, LocalContextCheckpointKind.WORK))
        assertTrue(isTrustedContextCheckpointModelMessage(work, LocalContextCheckpointKind.WORK))
        assertTrue(isTrustedContextCheckpointModelMessage(legacyWork, LocalContextCheckpointKind.WORK))
    }

    @Test
    fun chatCompactionWritesChatCheckpointThatWorkRecoveryWillIgnore() {
        val history = buildList {
            add(message("system", "系统"))
            repeat(8) { index ->
                add(message("user", "较早聊天-$index-" + "旧".repeat(500)))
                add(message("assistant", "较早回复-$index-" + "答".repeat(500)))
            }
            add(message("user", "当前聊天" + "新".repeat(300)))
            add(message("assistant", "当前回复" + "新".repeat(300)))
        }

        val compacted = LocalHistoryCompactor(
            maxHistoryChars = 800,
            tailChars = 500,
            maxSummaryChars = 1_200,
        ).compact(history, summaryMode = LocalHistorySummaryMode.CHAT)
            ?: error("expected chat compaction")

        assertTrue(compacted.messages.any {
            isTrustedContextCheckpointModelMessage(it, LocalContextCheckpointKind.CHAT)
        })
        assertFalse(compacted.messages.any {
            isTrustedContextCheckpointModelMessage(it, LocalContextCheckpointKind.WORK)
        })
        assertEquals(null, LocalWorkCheckpoint.latestFrom(compacted.messages))
    }

    @Test
    fun sharedProjectionDoesNotApplyWorkThresholdsToChat() {
        val messages = listOf(
            message("system", "规则"),
            message("user", "长聊天" + "聊".repeat(20_000)),
        )
        val tools = JsonArray(emptyList())
        val pressure = LocalPromptPressureMeter.measure(messages, tools, 100)

        val projected = projectLocalRequestContext(
            usageMode = LocalUsageMode.CHAT,
            workProjectionEnabled = true,
            messages = messages,
            tools = tools,
            compactor = LocalHistoryCompactor(maxHistoryChars = 10, tailChars = 5),
            operationalLimitTokens = 100,
            measuredPressure = pressure,
            previousSourcePressure = null,
            structuredWorkState = null,
            cachePolicy = LocalPromptCachePolicy(),
            allowSemanticProjection = true,
        )

        assertFalse(projected.projected)
        assertEquals(messages, projected.messages)
        assertEquals(LocalHistorySummaryMode.CHAT, LocalUsageMode.CHAT.historySummaryMode())
        assertEquals(LocalHistorySummaryMode.WORK, LocalUsageMode.WORK.historySummaryMode())
    }

    @Test
    fun requestPressureStoreKeepsModeSpecificSourceCoordinates() {
        val store = LocalRequestPressureStore()
        val chat = LocalPromptPressureMeter.measure(
            listOf(message("user", "聊天")), JsonArray(emptyList()), 10_000,
        )
        val work = LocalPromptPressureMeter.measure(
            listOf(message("user", "工作任务")), JsonArray(emptyList()), 10_000,
        )

        store.record("s", chat, usageMode = LocalUsageMode.CHAT, sourcePressure = chat)
        store.record("s", work, usageMode = LocalUsageMode.WORK, sourcePressure = work)

        assertEquals(chat, store.latest("s", LocalUsageMode.CHAT))
        assertEquals(work, store.latest("s", LocalUsageMode.WORK))
        assertEquals(chat, store.latestSource("s", LocalUsageMode.CHAT))
        assertEquals(work, store.latestSource("s", LocalUsageMode.WORK))
    }
}
