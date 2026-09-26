package com.labteto.dshmobile.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranscriptSnapshotMigrationTest {
    @Test
    fun migrationMovesLegacySnapshotBehindHardEventBoundary() {
        withLog { log ->
            log.append(
                "user/message",
                buildJsonObject {
                    put("transcript", encodeTranscriptMessages(listOf(message("old-duplicate", "user", "旧事件"))))
                },
            )
            val legacyMessages = (0 until 7).map { index ->
                message(
                    id = "m$index",
                    role = if (index % 2 == 0) "user" else "assistant",
                    content = "消息$index",
                )
            }
            val session = LocalHarnessSession(
                id = "legacy",
                messages = legacyMessages,
                transcriptIndex = buildLocalTranscriptRuntimeIndex(legacyMessages),
            )

            val migrated = migrateLegacyTranscriptSnapshot(
                session = session,
                eventLog = log,
                windowSize = 3,
                chunkSize = 2,
            )

            assertNotNull(migrated)
            assertEquals(listOf("m4", "m5", "m6"), migrated!!.window.map { it.id })
            assertEquals(7L, migrated.index.totalMessageCount)
            assertEquals(
                legacyMessages.map { it.id },
                LocalSessionTranscriptPager(log).all(pageSize = 2).map { it.id },
            )
            assertTrue(log.latest(LOCAL_TRANSCRIPT_MIGRATION_COMPLETE_EVENT) != null)
        }
    }

    @Test
    fun completedMigrationCanRecoverBeforeSlimSnapshotFlush() {
        withLog { log ->
            val legacyMessages = (0 until 5).map { index ->
                message("m$index", if (index % 2 == 0) "user" else "assistant", "消息$index")
            }
            val session = LocalHarnessSession(id = "legacy", messages = legacyMessages)

            val first = migrateLegacyTranscriptSnapshot(session, log, windowSize = 2, chunkSize = 2)
            val second = migrateLegacyTranscriptSnapshot(session, log, windowSize = 2, chunkSize = 2)

            assertNotNull(first)
            assertNotNull(second)
            assertEquals(listOf("m3", "m4"), second!!.window.map { it.id })
            assertEquals(5L, second.index.totalMessageCount)
            assertEquals(
                1,
                log.events().count { it.type == LOCAL_TRANSCRIPT_MIGRATION_BASELINE_EVENT },
            )
        }
    }

    private fun message(id: String, role: String, content: String) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        createdAt = id.hashCode().toLong(),
    )

    private fun withLog(block: (LocalSessionEventLog) -> Unit) {
        val root = createTempDir(prefix = "transcript-migration-")
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
