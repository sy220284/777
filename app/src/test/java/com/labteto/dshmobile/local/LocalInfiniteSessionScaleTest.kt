package com.labteto.dshmobile.local

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInfiniteSessionScaleTest {
    private val scales = listOf(1_000, 5_000, 10_000, 50_000)

    @Test
    fun newestTranscriptPageStaysBoundedAcrossLargeHistories() {
        scales.forEach { total ->
            withLog("page-$total") { log ->
                var offset = 0
                while (offset < total) {
                    val chunkSize = minOf(200, total - offset)
                    val messages = (0 until chunkSize).map { local ->
                        val index = offset + local
                        message(
                            id = "m$index",
                            role = if (index % 2 == 0) "user" else "assistant",
                            content = "消息$index",
                        )
                    }
                    log.append(
                        "transcript/batch",
                        buildJsonObject { put("transcript", encodeTranscriptMessages(messages)) },
                    )
                    offset += chunkSize
                }

                val page = LocalSessionTranscriptPager(log, eventPageSize = 4).page(limit = 200)

                assertEquals(200, page.messages.size)
                assertEquals("m${total - 200}", page.messages.first().id)
                assertEquals("m${total - 1}", page.messages.last().id)
                assertTrue(page.nextCursor != null)
            }
        }
    }

    @Test
    fun slimSessionSnapshotSizeDoesNotScaleWithHistoricalMessageCount() {
        val json = Json { encodeDefaults = true }
        val fixedWindow = (0 until 200).map { index ->
            message(
                id = "hot-$index",
                role = if (index % 2 == 0) "user" else "assistant",
                content = "固定热窗口消息-$index",
            )
        }

        val sizes = scales.map { total ->
            val snapshot = LocalHarnessSession(
                id = "scale-$total",
                title = "长会话",
                messages = emptyList(),
                transcriptWindow = fixedWindow,
                transcriptIndex = LocalTranscriptRuntimeIndex(
                    firstUserTitle = "最早标题",
                    latestCreatedAt = total.toLong(),
                    latestDialogueCreatedAt = total.toLong(),
                    latestDialogueMessageId = "latest",
                    latestUserMessageId = "latest-user",
                    latestUserContent = "最近问题",
                    latestMessageId = "latest",
                    latestMessageRole = "assistant",
                    totalMessageCount = total.toLong(),
                    hasDialogue = true,
                    branchingEligible = true,
                    lastTurnDialogueRole = "assistant",
                ),
                transcriptProjectedThroughSequence = total.toLong(),
            )
            json.encodeToString(LocalHarnessSession.serializer(), snapshot).length
        }

        assertTrue(
            "Session JSON should remain effectively constant across 1k..50k history: $sizes",
            (sizes.maxOrNull()!! - sizes.minOrNull()!!) < 128,
        )
    }

    @Test
    fun completeEventHistoryRemainsQueryableAfterPaging() {
        withLog("complete") { log ->
            repeat(5) { batch ->
                val messages = (0 until 200).map { local ->
                    val index = batch * 200 + local
                    message("m$index", if (index % 2 == 0) "user" else "assistant", "消息$index")
                }
                log.append(
                    "transcript/batch",
                    buildJsonObject { put("transcript", encodeTranscriptMessages(messages)) },
                )
            }

            val rebuilt = LocalSessionTranscriptPager(log, eventPageSize = 2).all(pageSize = 200)

            assertEquals(1_000, rebuilt.size)
            assertEquals("m0", rebuilt.first().id)
            assertEquals("m999", rebuilt.last().id)
        }
    }

    private fun message(id: String, role: String, content: String) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        createdAt = id.hashCode().toLong(),
    )

    private fun withLog(name: String, block: (LocalSessionEventLog) -> Unit) {
        val root = createTempDir(prefix = "infinite-session-$name-")
        try {
            val log = LocalSessionEventLog(
                file = File(root, "session.events.jsonl"),
                json = Json { ignoreUnknownKeys = true },
            )
            block(log)
        } finally {
            root.deleteRecursively()
        }
    }
}
