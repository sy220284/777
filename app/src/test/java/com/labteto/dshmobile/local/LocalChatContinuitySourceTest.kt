package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalChatContinuitySourceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sourceLookupSkipsLaterQueuedUserAndFindsExactTurnAcrossPages() {
        val log = LocalSessionEventLog(File(temporary.root, "events.jsonl"), json)
        val first = LocalHarnessMessage(
            id = "u-first",
            role = "user",
            content = "我们明天去城南吧",
            createdAt = 10L,
        )
        val queued = LocalHarnessMessage(
            id = "u-queued",
            role = "user",
            content = "对了还有另一件事",
            createdAt = 11L,
        )
        val assistant = LocalHarnessMessage(
            id = "a-first",
            role = "assistant",
            content = "好，明天去城南。",
            createdAt = 12L,
        )

        log.append("user/message", buildJsonObject {
            put("transcript", encodeTranscriptMessages(listOf(first)))
        })
        log.append("agent/inbox/spliced", buildJsonObject {
            put("transcript", encodeTranscriptMessages(listOf(queued)))
        })
        val assistantEvent = log.append("assistant/message", buildJsonObject {
            put("transcript", encodeTranscriptMessages(listOf(assistant)))
        })

        assertEquals(
            "u-first",
            findChatContinuitySourceUserMessageId(
                eventLog = log,
                beforeSequenceExclusive = assistantEvent.sequence,
                expectedContent = first.content,
                pageSize = 1,
            ),
        )
    }

    @Test
    fun sourceLookupNeverBindsDifferentUserText() {
        val log = LocalSessionEventLog(File(temporary.root, "events-mismatch.jsonl"), json)
        val user = LocalHarnessMessage(
            id = "u1",
            role = "user",
            content = "完全不同的话",
            createdAt = 1L,
        )
        val assistant = LocalHarnessMessage(
            id = "a1",
            role = "assistant",
            content = "收到",
            createdAt = 2L,
        )
        log.append("user/message", buildJsonObject {
            put("transcript", encodeTranscriptMessages(listOf(user)))
        })
        val assistantEvent = log.append("assistant/message", buildJsonObject {
            put("transcript", encodeTranscriptMessages(listOf(assistant)))
        })

        assertEquals(
            null,
            findChatContinuitySourceUserMessageId(
                eventLog = log,
                beforeSequenceExclusive = assistantEvent.sequence,
                expectedContent = "我要找的其实是另一条消息",
                pageSize = 1,
            ),
        )
    }
}
