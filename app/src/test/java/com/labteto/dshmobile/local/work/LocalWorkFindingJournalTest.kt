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
            assertFalse(LocalWorkFindingJournal.supportedForReplan(history, "goal-a", id))
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
            assertTrue(LocalWorkFindingJournal.supportedForReplan(history, "goal-a", id))
            assertFalse(LocalWorkFindingJournal.supportedForReplan(history, "another-goal", id))
            history.tool("read-3")
            LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                put("action", "revise")
                put("finding_id", id)
                put("source_call_id", "read-3")
                put("summary", "进一步检查后确认此前的公共实现不适用")
                put("status", "rejected")
            })
            assertFalse(LocalWorkFindingJournal.supportedForReplan(history, "goal-a", id))
            assertEquals(3, history.eventsOfTypesAfter(-1L, setOf("work/finding")).size)
        } finally {
            history.close()
        }
    }

    @Test fun retractRemovesFindingFromLiveViewsButKeepsOriginalEvidence() {
        val history = log()
        try {
            history.tool("read-with-evidence")
            val recorded = LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                put("action", "record")
                put("summary", "早期检查提示应当复用这套可能重复的代码实现")
                put("status", "supported")
                put("source_call_id", "read-with-evidence")
            })
            val id = recorded.substringAfter("ID=").substringBefore("；")
            assertTrue(LocalWorkFindingJournal.supportedForReplan(history, "goal-a", id))
            assertTrue(runCatching {
                LocalWorkFindingJournal.execute(history, "another-goal", buildJsonObject {
                    put("action", "retract"); put("finding_id", id)
                })
            }.isFailure)
            val response = LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                put("action", "retract")
                put("finding_id", id)
            })
            assertTrue(response.contains("已撤回"))
            assertFalse(LocalWorkFindingJournal.supportedForReplan(history, "goal-a", id))
            assertEquals("当前目标没有调查线索",
                LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                    put("action", "list")
                }))
            val events = history.eventsOfTypesAfter(-1L, setOf("work/finding"))
            assertEquals(2, events.size)
            assertEquals("supported", events.first().data["status"].toString().trim('"'))
            assertEquals("retracted", events.last().data["status"].toString().trim('"'))
            assertTrue(runCatching {
                LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                    put("action", "retract"); put("finding_id", id)
                })
            }.isFailure)
            history.tool("new-read")
            assertTrue(runCatching {
                LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                    put("action", "revise"); put("finding_id", id)
                    put("summary", "已撤回记录需要重新调查而不能复活")
                    put("source_call_id", "new-read")
                })
            }.isFailure)
        } finally { history.close() }
    }


    @Test fun gatewayReceiptIdIsDiscoverableAndCanBeCited() {
        val history = log()
        try {
            val source = history.append("tool/call", buildJsonObject {
                put("id", "outer-tool-call-1")
                put("goal_id", "goal-a")
                put("name", "tool_call")
                put("arguments", buildJsonObject { put("name", "read") })
            })
            history.append("tool/result", buildJsonObject {
                put("id", "outer-tool-call-1")
                put("is_error", false)
                put("tool_declared_sequence", source.sequence)
            })
            val failed = runCatching {
                LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                    put("action", "record")
                    put("source_call_id", "unavailable-id")
                    put("summary", "核实网关工具真实回执与目标的关联记录")
                })
            }.exceptionOrNull()!!.message.orEmpty()
            assertTrue(failed, failed.contains("outer-tool-call-1（read）"))
            val recorded = LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                put("action", "record")
                put("source_call_id", "outer-tool-call-1")
                put("summary", "已取得来自当前目标下的成功读取证据回执")
            })
            assertTrue(recorded.contains("来源回执=outer-tool-call-1"))
        } finally { history.close() }
    }

    @Test fun workFindingGatewayReceiptCannotCiteItselfAsEvidence() {
        val history = log()
        try {
            val source = history.append("tool/call", buildJsonObject {
                put("id", "gateway-find-1")
                put("goal_id", "goal-a")
                put("name", "tool_call")
                put("arguments", buildJsonObject { put("name", "work_finding") })
            })
            history.append("tool/result", buildJsonObject {
                put("id", "gateway-find-1")
                put("is_error", false)
                put("tool_declared_sequence", source.sequence)
            })
            val failed = runCatching {
                LocalWorkFindingJournal.execute(history, "goal-a", buildJsonObject {
                    put("action", "record")
                    put("source_call_id", "gateway-find-1")
                    put("summary", "调查发现不能把自身回执作为事实来源")
                })
            }.exceptionOrNull()!!.message.orEmpty()
            assertTrue(failed, failed.contains("来源工具不属于当前目标"))
            assertFalse(failed.contains("可用的来源回执 ID：gateway-find-1"))
            assertTrue(history.eventsOfTypesAfter(-1L, setOf("work/finding")).isEmpty())
        } finally { history.close() }
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
