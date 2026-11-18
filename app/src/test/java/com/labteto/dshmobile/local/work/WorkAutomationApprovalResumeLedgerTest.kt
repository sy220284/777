package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class WorkAutomationApprovalResumeLedgerTest {
    private fun withLog(block: (LocalSessionEventLog, WorkAutomationApprovalResumeLedger) -> Unit) {
        val file = File(System.getProperty("java.io.tmpdir"),
            "approval-resume-${UUID.randomUUID()}.events.jsonl")
        val log = LocalSessionEventLog(file, Json)
        try {
            block(log, WorkAutomationApprovalResumeLedger({ log }, Unit))
        } finally {
            log.close()
            file.delete()
        }
    }
    private fun blocked(log: LocalSessionEventLog, callId: String = "call-1") {
        val args = buildJsonObject { put("path", "notes.txt"); put("content", "keep") }
        log.append("automation/approval-step",buildJsonObject {
            put("call_id",callId)
            put("agent_id","sa-aut")
            put("step",1)
            put("history",buildJsonArray {
                add(buildJsonObject {
                    put("role","assistant")
                    put("tool_calls",buildJsonArray {
                        add(buildJsonObject {
                            put("id",callId)
                            put("name","write")
                            put("arguments",args)
                        })
                    })
                })
            })
        })
        log.append("approval/blocked",buildJsonObject {
            put("call_id",callId)
            put("tool","write")
            put("arguments",args)
            put("summary","向 notes.txt 写入指定内容")
        })
    }
    @Test fun exactSingleCallGrantPreservesHistoryAndNeverRunsResultTwice() = withLog { log, ledger ->
        blocked(log)
        assertNull(ledger.authorized("session"))
        assertTrue(ledger.authorize("session"))
        assertFalse(ledger.authorize("session"))
        val pending = requireNotNull(ledger.authorized("session"))
        assertEquals("call-1",pending.call.id)
        assertEquals("write",pending.call.name)
        var invocations = 0
        val first = runBlocking {
            ledger.execute(pending) { call ->
                invocations++
                assertEquals("notes.txt",call.arguments["path"]?.jsonPrimitive?.content)
                AgentToolResult("written-once",sideEffect=AgentToolSideEffect.POSSIBLE)
            }
        }
        assertEquals(1,invocations)
        assertEquals(1,first.step)
        assertEquals("tool",first.history.last()["role"]?.jsonPrimitive?.content)
        assertEquals("call-1",first.history.last()["tool_call_id"]?.jsonPrimitive?.content)
        val recovered = runBlocking {
            ledger.execute(pending) { invocations++; AgentToolResult("unexpected") }
        }
        assertEquals(1,invocations)
        assertEquals(first.history,recovered.history)
        ledger.finalize("session",pending,"delivered","done")
        assertNull(ledger.authorized("session"))
    }
    @Test fun ambiguousStartedExternalEffectBlocksAutomaticReplay() = withLog { log, ledger ->
        blocked(log)
        assertTrue(ledger.authorize("session"))
        val pending = requireNotNull(ledger.authorized("session"))
        log.append("approval/resume-execution-started",buildJsonObject {
            put("blocked_sequence",pending.blockSequence)
            put("call_id",pending.call.id)
        })
        var invocations=0
        val error=runCatching {
            runBlocking { ledger.execute(pending) { invocations++; AgentToolResult("unexpected") } }
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("核对外部结果"))
        assertEquals(0,invocations)
        assertFalse(ledger.authorize("session"))
    }
    @Test fun missingExactModelHistoryCannotAuthorize() = withLog { log, ledger ->
        log.append("approval/blocked", buildJsonObject {
            put("tool","write")
            put("call_id","unknown")
            put("arguments",buildJsonObject { put("path","danger") })
        })
        assertFalse(ledger.authorize("session"))
        assertNull(ledger.authorized("session"))
    }
}
