package com.labteto.dshmobile.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionTranscriptPagerTest {
    @Test
    fun pagesNewestTranscriptWithoutScanningOnlyOneEventChunk() {
        withLog { log ->
            log.append("user/message", transcript(message("m1", "user", "一")))
            repeat(5) { index ->
                log.append("request/header", buildJsonObject { put("index", index) })
            }
            log.append("assistant/message", transcript(message("m2", "assistant", "二")))
            repeat(5) { index ->
                log.append("step/end", buildJsonObject { put("index", index) })
            }
            log.append("user/message", transcript(message("m3", "user", "三")))

            val pager = LocalSessionTranscriptPager(log, eventPageSize = 2)
            val first = pager.page(limit = 2)
            assertEquals(listOf("m2", "m3"), first.messages.map { it.id })

            val second = pager.page(first.nextCursor, limit = 2)
            assertEquals(listOf("m1"), second.messages.map { it.id })
            assertNull(second.nextCursor)
        }
    }

    @Test
    fun resumesInsideOneEventWithoutDroppingMessages() {
        withLog { log ->
            val messages = (1..5).map { index ->
                message("m$index", if (index % 2 == 0) "assistant" else "user", "消息$index")
            }
            log.append("chat/active-transcript", buildJsonObject {
                put("transcript", encodeTranscriptMessages(messages))
            })

            val pager = LocalSessionTranscriptPager(log)
            val first = pager.page(limit = 2)
            assertEquals(listOf("m4", "m5"), first.messages.map { it.id })

            val second = pager.page(first.nextCursor, limit = 2)
            assertEquals(listOf("m2", "m3"), second.messages.map { it.id })

            val third = pager.page(second.nextCursor, limit = 2)
            assertEquals(listOf("m1"), third.messages.map { it.id })
            assertNull(third.nextCursor)
        }
    }

    @Test
    fun replacementStateCrossesPageBoundariesAndSuppressesOldBubble() {
        withLog { log ->
            log.append("assistant/message", transcript(message("old", "assistant", "旧回答")))
            log.append("user/message", transcript(message("middle", "user", "中间消息")))
            log.append("assistant/message", buildJsonObject {
                put("replaces", "old")
                put("transcript", encodeTranscriptMessages(listOf(message("new", "assistant", "新回答"))))
            })
            log.append("user/message", transcript(message("latest", "user", "最新消息")))

            val pager = LocalSessionTranscriptPager(log)
            var cursor: LocalTranscriptPageCursor? = null
            val ids = mutableListOf<String>()
            do {
                val page = pager.page(cursor, limit = 1)
                ids += page.messages.map { it.id }
                cursor = page.nextCursor
            } while (cursor != null)

            assertEquals(listOf("latest", "new", "middle"), ids)
            assertTrue("old" !in ids)
        }
    }

    @Test
    fun activeTranscriptIsMaterializedBoundaryForOlderBranchEvents() {
        withLog { log ->
            log.append("user/message", transcript(message("old-user", "user", "原问题")))
            log.append("assistant/message", transcript(message("old-answer", "assistant", "原回答")))
            log.append("chat/active-transcript", buildJsonObject {
                put(
                    "transcript",
                    encodeTranscriptMessages(
                        listOf(
                            message("edited-user", "user", "修改问题"),
                            message("edited-answer", "assistant", "新回答"),
                        ),
                    ),
                )
            })
            log.append("user/message", transcript(message("latest", "user", "继续")))

            val pager = LocalSessionTranscriptPager(log)
            var cursor: LocalTranscriptPageCursor? = null
            val pages = mutableListOf<List<String>>()
            do {
                val page = pager.page(cursor, limit = 1)
                pages += page.messages.map { it.id }
                cursor = page.nextCursor
            } while (cursor != null)

            assertEquals(
                listOf(listOf("latest"), listOf("edited-answer"), listOf("edited-user")),
                pages,
            )
            assertTrue(pages.flatten().none { it.startsWith("old-") })
        }
    }

    @Test
    fun malformedActiveTranscriptDoesNotHideEarlierValidHistory() {
        withLog { log ->
            log.append("user/message", transcript(message("before", "user", "有效旧消息")))
            log.append("chat/active-transcript", buildJsonObject {
                put(
                    "transcript",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", "broken")
                                put("role", "assistant")
                            },
                        ),
                    ),
                )
            })
            log.append("assistant/message", transcript(message("after", "assistant", "有效新消息")))

            val page = LocalSessionTranscriptPager(log, eventPageSize = 1).page(limit = 10)
            assertEquals(listOf("before", "after"), page.messages.map { it.id })
            assertNull(page.nextCursor)
        }
    }

    private fun transcript(vararg messages: LocalHarnessMessage) = buildJsonObject {
        put("transcript", encodeTranscriptMessages(messages.toList()))
    }

    private fun message(id: String, role: String, content: String) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        createdAt = id.hashCode().toLong(),
    )

    private fun withLog(block: (LocalSessionEventLog) -> Unit) {
        val root = createTempDir(prefix = "transcript-pager-")
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
