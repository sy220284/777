package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.automation.recoverAutomationChatOutput
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAutomationChatRecoveryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun recoveryPagesBackwardAcrossLongTailAndClosesInterruptedTurn() {
        val log = LocalSessionEventLog(
            File(temporary.root, "automation.events.jsonl"),
            json,
        )
        val threshold = System.currentTimeMillis() - 1_000L
        log.append("turn/start", buildJsonObject {
            put("automation", true)
            put("proactive", true)
        })
        val proactive = LocalHarnessMessage(
            id = "a-proactive",
            role = "assistant",
            content = "突然想起你了。",
            createdAt = System.currentTimeMillis(),
            proactive = true,
        )
        log.append("assistant/message", buildJsonObject {
            put("transcript", encodeTranscriptMessages(listOf(proactive)))
        })
        repeat(260) { index ->
            log.append("noise/event", buildJsonObject { put("index", index) })
        }

        val recovered = recoverAutomationChatOutput(log, threshold)

        assertEquals("突然想起你了。", recovered)
        val end = log.latest("turn/end")
        assertNotNull(end)
        assertEquals(
            true,
            end!!.data["recovered"]?.jsonPrimitive?.booleanOrNull,
        )
    }

    @Test
    fun latestProactiveStartWithoutDurableReplyDoesNotReuseOlderOutput() {
        val log = LocalSessionEventLog(
            File(temporary.root, "latest.events.jsonl"),
            json,
        )
        val threshold = System.currentTimeMillis() - 1_000L
        log.append("turn/start", buildJsonObject {
            put("automation", true)
            put("proactive", true)
        })
        val older = LocalHarnessMessage(
            id = "a-old",
            role = "assistant",
            content = "旧主动消息",
            createdAt = System.currentTimeMillis(),
            proactive = true,
        )
        log.append("assistant/message", buildJsonObject {
            put("transcript", encodeTranscriptMessages(listOf(older)))
        })
        log.append("turn/end", buildJsonObject { put("reason", "completed") })
        log.append("turn/start", buildJsonObject {
            put("automation", true)
            put("proactive", true)
        })

        assertEquals(null, recoverAutomationChatOutput(log, threshold))
    }
}
