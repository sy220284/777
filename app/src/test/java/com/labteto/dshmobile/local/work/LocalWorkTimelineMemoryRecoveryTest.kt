package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkTimelineMemoryRecoveryTest {
    @Test
    fun removesDiscardedMemoryAndRecoversOnlyOnceAfterInterruptedRewrite() {
        val root = kotlin.io.path.createTempDirectory("work-edit-memory-").toFile()
        try {
            val memory = MemoryStore(File(root, "memory"), Json)
            val original = memory.remember(
                "允许保留的旧记忆", MemoryScope.GLOBAL,
                sourceSessionId = "session-a", sourceMessageId = "u1",
            )
            memory.remember(
                "应当撤销的旧记忆", MemoryScope.GLOBAL,
                sourceSessionId = "session-a", sourceMessageId = "u2",
            )
            val unrelated = memory.remember(
                "另一会话的记忆", MemoryScope.GLOBAL,
                sourceSessionId = "session-b", sourceMessageId = "u2",
            )
            val log = LocalSessionEventLog(File(root, "session.events.jsonl"), Json)
            log.append("work/active-transcript", buildJsonObject {
                put("edit_id", "edit-1")
                put("memory_rollback", buildJsonObject {
                    put("session_id", "session-a")
                    put("created_at", Long.MAX_VALUE)
                    put("message_ids", JsonArray(listOf(JsonPrimitive("u2"))))
                })
            })
            val recovered = LocalWorkTimelineMemoryRecovery(memory)
            assertTrue(recovered.recover(log))
            assertFalse(recovered.recover(log))

            val restarted = MemoryStore(File(root, "memory"), Json)
            val active = restarted.listActive(
                MemoryScope.values().toSet(), "project", "lineage", limit = 100,
            )
            assertEquals(setOf(original.id, unrelated.id), active.map { it.id }.toSet())
            assertFalse(LocalWorkTimelineMemoryRecovery(restarted).recover(log))
        } finally {
            root.deleteRecursively()
        }
    }
}
