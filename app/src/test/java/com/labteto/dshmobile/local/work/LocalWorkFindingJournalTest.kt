package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkFindingJournalTest {
    @get:Rule val folder = TemporaryFolder()

    private fun log() = LocalSessionEventLog(
        File(folder.root, "journal-${System.nanoTime()}.events.jsonl"),
        Json { ignoreUnknownKeys = true },
        sessionId = "test-work-finding",
    )

    private fun LocalSessionEventLog.tool(id: String, goal: String = "goal-a", failed: Boolean = false) {
        val declaration = append("tool/call", buildJsonObject {
            put("id", id); put("goal_id", goal); put("execution_started", false)
        })
        append("tool/result", buildJsonObject {
            put("id", id); put("is_error", failed)
            put("tool_declared_sequence", declaration.sequence)
        })
    }

    @Test fun recordsFindingsWithRealSourceAndAllowsRevisionAndRecall() {
        val history = log()
        try {
            history.tool("read-1")
            val recorded = LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                put("action", "record")
                put("summary", "模块存在重复功能，需继续核实底层调用关系")
                put("source_call_id", "read-1")
                put("next_action", "检查已有公共实现")
            })
            val id = recorded.substringAfter("ID=").substringBefore("；")
            assertTrue(id.startsWith("finding-"))
            assertTrue(LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                put("action", "list")
            }).contains("模块存在重复功能"))

            history.tool("read-2")
            LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                put("action", "revise")
                put("finding_id", id)
                put("source_call_id", "read-2")
                put("summary", "找到可复用的公共实现，先检查是否覆盖全部调用")
                put("status", "supported")
            })
            val list = LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                put("action", "list")
            })
            assertTrue(list.contains("找到可复用的公共实现"))
            assertFalse(list.contains("模块存在重复功能"))
            assertEquals(2, history.eventsOfTypesAfter(-1L, setOf("work/finding")).size)
        } finally {
            history.close()
        }
    }

    @Test fun rejectsInventedFailedOrOtherGoalReceiptsAndUnrelatedRevisions() {
        val history = log()
        try {
            history.tool("other", goal = "goal-b")
            history.tool("failed", failed = true)
            for (sourceId in listOf("missing", "other", "failed")) {
                val result = runCatching {
                    LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                        put("action", "record")
                        put("source_call_id", sourceId)
                        put("summary", "没有实际完成的来源不能记录为已支持的发现")
                    })
                }
                assertTrue(result.isFailure)
            }
            assertTrue(runCatching {
                LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                    put("action", "revise")
                    put("finding_id", "finding-not-exist")
                    put("source_call_id", "other")
                    put("summary", "不能修改不属于当前目标的发现记录")
                })
            }.isFailure)
            history.append("tool/call", buildJsonObject {
                put("id", "reused"); put("goal_id", "goal-a")
            })
            history.append("tool/result", buildJsonObject {
                put("id", "reused"); put("is_error", false)
                put("tool_declared_sequence", 999999)
            })
            assertTrue(runCatching {
                LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                    put("action", "record")
                    put("source_call_id", "reused")
                    put("summary", "不能把不匹配的调用回执当成有效来源")
                })
            }.isFailure)
            assertTrue(history.eventsOfTypesAfter(-1L, setOf("work/finding")).isEmpty())
        } finally {
            history.close()
        }
    }
}
