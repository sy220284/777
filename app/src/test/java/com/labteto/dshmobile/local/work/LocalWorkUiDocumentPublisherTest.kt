package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.session.LocalUiDocumentSessionStore
import com.labteto.dshmobile.uicontract.UiDocumentBudget
import com.labteto.dshmobile.uicontract.UiDocumentNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkUiDocumentPublisherTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun interruptedSettlementsReplacePendingRowsAndNeverBorrowAnotherTurnsPhase() {
        val logs = LocalSessionEventLogRegistry(temporary.root, Json)
        val documents = LocalUiDocumentSessionStore(logs)
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(LocalHarnessState(sessionId = "work", usageMode = LocalUsageMode.WORK, loading = false))
        val log = logs.get("work")
        val binding = LocalWorkRunBinding("work", LocalWorkRunState(sessionId = "work"),
            LocalHarnessSession(id = "work", usageMode = LocalUsageMode.WORK),
            LocalAgentRunHandle(initialSessionId = "work", maxPendingInputs = 4), log)
        val publisher = LocalWorkUiDocumentPublisher(documents, runtime)
        val budget = UiDocumentBudget(512, 16, 262_144)
        try {
            for ((source, started, readOnly) in listOf(
                Triple("cancelled-write", true, false),
                Triple("unstarted", false, false),
                Triple("interrupted-read", true, true),
            )) {
                log.append("tool/call", buildJsonObject {
                    put("source_message_id", source); put("id", "call-$source"); put("name", "tool-$source")
                })
                publisher.onToolResult(binding, source)
                val pending = documents.latestDocument("work", source, "work-live:$source", budget, emptySet())!!.document
                assertTrue((pending.nodes.filterIsInstance<UiDocumentNode.Table>().single()).rows.single()[1].contains("等待"))
                val settlement = SessionRecovery.interruptedToolResult("call-$source", "tool-$source", 1, started, readOnly)
                val event = interruptedWorkToolEvent(settlement, source, "cancelled")
                assertEquals(source, event.getValue("source_message_id").jsonPrimitive.content)
                assertEquals(settlement.code != SessionRecovery.TOOL_OUTCOME_UNKNOWN,
                    event.getValue("retryable").jsonPrimitive.content.toBoolean())
                log.append("tool/result", event)
                log.append("turn/end", buildJsonObject { put("source_message_id", source); put("reason", "aborted") })
                publisher.onToolResult(binding, source)
                val finished = documents.latestDocument("work", source, "work-live:$source", budget, emptySet())!!.document
                val row = finished.nodes.filterIsInstance<UiDocumentNode.Table>().single().rows.single()
                assertEquals(settlement.code, row[2])
                assertFalse(row[1].contains("等待实际回执"))
                if (started && !readOnly) assertTrue(row[1].contains("勿直接重试"))
                assertTrue(finished.nodes.none { it is UiDocumentNode.BoundText })
                assertTrue(finished.nodes.filterIsInstance<UiDocumentNode.Text>().any { it.text == "本轮已终止" })
                assertTrue(finished.actions.isEmpty())
            }
            log.append("tool/call", buildJsonObject {
                put("source_message_id", "new-turn"); put("id", "other-call"); put("name", "another-tool")
            })
            publisher.onToolResult(binding, "new-turn")
            val old = documents.latestDocument("work", "cancelled-write", "work-live:cancelled-write", budget, emptySet())!!.document
            assertEquals("tool-cancelled-write", old.nodes.filterIsInstance<UiDocumentNode.Table>().single().rows.single()[0])
            assertTrue(old.nodes.none { it is UiDocumentNode.BoundText })
        } finally { logs.clearAndEvict(setOf("work")) }
    }
}
