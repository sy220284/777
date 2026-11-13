package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.restoreLocalModelHistory
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import com.labteto.dshmobile.local.session.projectLocalTranscriptRuntimeIndexTail
import com.labteto.dshmobile.local.session.projectSessionTranscriptTail
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkHistoricalEditRecoveryTest {
    @Test
    fun workHistoricalEditHidesDiscardedTailAndRestoresConsistentVisibleAndModelHistory() {
        val root = kotlin.io.path.createTempDirectory("work-user-edit-").toFile()
        try {
            val log = LocalSessionEventLog(File(root, "session.events.jsonl"), Json { ignoreUnknownKeys = true })
            val u1 = message("u1", "user", "最初的请求")
            val a1 = message("a1", "assistant", "最初的回复")
            val u2 = message("u2", "user", "后来要修改的请求")
            val a2 = message("a2", "assistant", "已弃用的答复")
            val edited = message("u3", "user", "改后的请求")
            val newAnswer = message("a3", "assistant", "新答复")
            log.append("system/prompt", buildJsonObject { put("content", "工作系统提示") })
            log.append("user/message", eventText(u1))
            log.append("assistant/message", eventText(a1))
            log.append("plan/state", buildJsonObject {
                put("items", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("原计划"))))
            })
            log.append("user/message", eventText(u2))
            log.append("assistant/message", eventText(a2))
            // The old Work tool and its side effects stay in the audit log, not in model context.
            log.append("tool/result", buildJsonObject { put("content", "过去的工具副作用") })
            val prefixModel = listOf(
                model("system", "工作系统提示"),
                model("user", "最初的请求"),
                model("assistant", "最初的回复"),
            )
            log.append("work/active-transcript", buildJsonObject {
                put("reason", "user-edited")
                put("transcript", encodeTranscriptMessages(listOf(u1, a1)))
                put("model_history", JsonArray(prefixModel))
                put("plan", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("原计划"))))
                put("todos", JsonArray(emptyList()))
                put("goal", kotlinx.serialization.json.JsonNull)
                put("plan_mode", false)
            })
            log.append("user/message", eventText(edited))
            log.append("assistant/message", eventText(newAnswer))

            val allEvents = log.snapshot()
            val expectedIds = listOf("u1", "a1", "u3", "a3")
            assertEquals(expectedIds, LocalSessionTranscriptPager(log, eventPageSize = 2).all(pageSize = 2).map { it.id })
            val projected = projectSessionTranscriptTail(emptyList(), allEvents, -1L)
            assertEquals(expectedIds, projected.messages.map { it.id })
            val index = projectLocalTranscriptRuntimeIndexTail(LocalTranscriptRuntimeIndex(), allEvents, -1L)
            assertEquals(4L, index.totalMessageCount)
            assertEquals("u3", index.latestUserMessageId)

            val restored = restoreLocalModelHistory(allEvents, emptyList(), ModelHistoryCheckpointCodec())
            assertEquals(
                listOf("工作系统提示", "最初的请求", "最初的回复", "改后的请求", "新答复"),
                restored.messages.map { it["content"]?.jsonPrimitive?.content },
            )
            assertTrue(restored.messages.none { it.toString().contains("已弃用的答复") })
            val controls = projectWorkSessionControls(LocalWorkState(), allEvents)
            assertEquals(listOf("原计划"), controls.plan)
            assertTrue(controls.todos.isEmpty())
            assertEquals(null, controls.goal)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun workEditableTextUsesStructuredBlocksAndDoesNotExposeLegacyAttachmentInstructions() {
        val message = message("user", "user", "提问\n\n本次附件已导入本机工作区：\n- 文件：a.pdf")
        assertEquals("提问", userEditableText(message))
        val structured = message.copy(
            blocks = listOf(
                com.labteto.dshmobile.local.session.LocalMessageBlock.Text("原始提问"),
                com.labteto.dshmobile.local.session.LocalMessageBlock.File("a.pdf", "application/pdf", "a.pdf", 10L),
            ),
        )
        assertEquals("原始提问", userEditableText(structured))
    }

    private fun message(id: String, role: String, content: String) =
        LocalHarnessMessage(id, role, content, createdAt = id.hashCode().toLong())

    private fun model(role: String, content: String) =
        buildJsonObject { put("role", role); put("content", content) }

    private fun eventText(message: LocalHarnessMessage) = buildJsonObject {
        put("content", message.content)
        put("role", message.role)
        put("transcript", encodeTranscriptMessages(listOf(message)))
    }
}
