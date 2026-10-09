package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.project.DEFAULT_PROJECT_ID
import com.labteto.dshmobile.local.project.LocalProject
import com.labteto.dshmobile.local.project.LocalProjectCatalogState
import com.labteto.dshmobile.local.project.planProjectDeletion
import com.labteto.dshmobile.local.project.resolveLocalProjectInstructions
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionRepository
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-owner offline acceptance paths: Session storage, Project rules, EventLog and transcript UI paging. */
class OfflineCrossFeatureAcceptanceTest {
    @Test
    fun missingProjectAfterCatalogResetRejectsColdSessionBeforeWorkRulesAreSilentlyDropped() = runTest {
        val root = kotlin.io.path.createTempDirectory("cross-project-restart-").toFile()
        try {
            val repository = LocalSessionRepository(root, Json, backgroundScope, {}, {})
            repository.writeNow(LocalHarnessSession(id = "work-a", title = "项目任务", projectId = "project-a"))
            val selected = LocalProjectCatalogState(
                projects = listOf(
                    LocalProject(DEFAULT_PROJECT_ID, "默认项目"),
                    LocalProject("project-a", "项目 A", "必须保留的项目规则"),
                ),
                activeId = "project-a",
            )
            val cold = LocalSessionRepository(root, Json, backgroundScope, {}, {}).read("work-a")!!
            assertEquals("project-a", cold.projectId)
            assertEquals("必须保留的项目规则",
                resolveLocalProjectInstructions(selected, null, cold.projectId))
            assertThrows(IllegalArgumentException::class.java) {
                resolveLocalProjectInstructions(LocalProjectCatalogState(), null, cold.projectId)
            }
            assertEquals("project-a",
                LocalSessionRepository(root, Json, backgroundScope, {}, {}).read("work-a")!!.projectId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun offscreenSessionReferencesPreventProjectDeletionUntilAllAreRemoved() = runTest {
        val root = kotlin.io.path.createTempDirectory("cross-project-delete-").toFile()
        try {
            val repo = LocalSessionRepository(root, Json, backgroundScope, {}, {})
            repeat(48) { index ->
                repo.writeNow(LocalHarnessSession(
                    id = "session-$index",
                    projectId = if (index == 47) "protected" else "other",
                    title = "历史会话 $index",
                ))
            }
            val catalog = LocalProjectCatalogState(
                projects = listOf(
                    LocalProject(DEFAULT_PROJECT_ID, "默认"),
                    LocalProject("protected", "受保护"),
                    LocalProject("other", "其他"),
                ),
                activeId = "protected",
            )
            fun ensureUnreferenced(id: String) {
                check(repo.summaries().none { it.projectId == id }) { "历史会话仍引用该项目" }
            }
            assertThrows(IllegalStateException::class.java) {
                planProjectDeletion(catalog, "protected", ::ensureUnreferenced)
            }
            assertEquals("protected", catalog.activeId)
            assertTrue(repo.delete("session-47"))
            val remaining = planProjectDeletion(catalog, "protected", ::ensureUnreferenced)
            assertEquals(DEFAULT_PROJECT_ID, remaining.activeId)
            assertFalse(remaining.projects.any { it.id == "protected" })
            assertEquals(47, LocalSessionRepository(root, Json, backgroundScope, {}, {}).summaries().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun coldRestartRetainsAllMessagesAcrossRotatedSegmentsAndToolNoise() {
        val root = kotlin.io.path.createTempDirectory("cross-history-scale-").toFile()
        val file = File(root, "session.events.jsonl")
        val json = Json { ignoreUnknownKeys = true }
        try {
            val writer = LocalSessionEventLog(file, json, maxBytes = 8_192)
            repeat(32) { batch ->
                val messages = (0 until 32).map { offset ->
                    val id = batch * 32 + offset
                    LocalHarnessMessage(
                        id = "m$id",
                        role = if (id % 2 == 0) "user" else "assistant",
                        content = "历史消息 $id",
                        createdAt = id.toLong(),
                    )
                }
                writer.append("transcript/batch",
                    buildJsonObject { put("transcript", encodeTranscriptMessages(messages)) })
                writer.append("tool/result", buildJsonObject { put("batch", batch) })
            }
            writer.close()
            val reopened = LocalSessionEventLog(file, json, maxBytes = 8_192)
            try {
                val pager = LocalSessionTranscriptPager(reopened, eventPageSize = 3)
                val history = pager.all(pageSize = 37)
                assertEquals(1_024, history.size)
                assertEquals((0 until 1_024).map { "m$it" }, history.map { it.id })
                assertEquals(1_024, history.map { it.id }.toSet().size)
                val newest = pager.page(limit = 5)
                assertEquals((1019..1023).map { "m$it" }, newest.messages.map { it.id })
                assertTrue(newest.nextCursor != null)
                assertEquals(63L, reopened.latestSequence())
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
