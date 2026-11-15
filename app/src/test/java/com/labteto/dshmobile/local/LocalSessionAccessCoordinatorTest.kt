package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.session.LocalSessionAccessCoordinator
import com.labteto.dshmobile.local.session.LocalSessionAccessScope
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionSummary
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionAccessCoordinatorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun activeDetachedSessionKeepsLineageAccessWithoutPersistedSummary() {
        val directory = Files.createTempDirectory("local-session-access-active").toFile()
        try {
            val currentLog = LocalSessionEventLog(directory.resolve("current.events.jsonl"), json)
            val activeLog = LocalSessionEventLog(directory.resolve("active.events.jsonl"), json)
            val coordinator = LocalSessionAccessCoordinator(
                summaries = { emptyList() },
                currentSessionId = { "current" },
                currentScope = { LocalSessionAccessScope(projectId = null, lineageId = "lineage-a") },
                activeScope = { sessionId ->
                    if (sessionId == "active") {
                        LocalSessionAccessScope(projectId = null, lineageId = "lineage-a")
                    } else {
                        null
                    }
                },
                eventLogFor = { sessionId -> if (sessionId == "active") activeLog else currentLog },
            )

            assertSame(activeLog, coordinator.authorizedLog("active"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeDetachedSessionFromAnotherLineageRemainsRejected() {
        val directory = Files.createTempDirectory("local-session-access-reject").toFile()
        try {
            val coordinator = LocalSessionAccessCoordinator(
                summaries = { emptyList() },
                currentSessionId = { "current" },
                currentScope = { LocalSessionAccessScope(projectId = null, lineageId = "lineage-a") },
                activeScope = { sessionId ->
                    if (sessionId == "other") {
                        LocalSessionAccessScope(projectId = null, lineageId = "lineage-b")
                    } else {
                        null
                    }
                },
                eventLogFor = { sessionId ->
                    LocalSessionEventLog(directory.resolve("$sessionId.events.jsonl"), json)
                },
            )

            val failure = runCatching { coordinator.authorizedLog("other") }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message?.contains("会话不存在或不属于当前项目/会话链") == true)
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun searchContinuesAfterSixtyFourSessionsWithoutLeakingOtherProjectSessions() {
        val directory = Files.createTempDirectory("local-session-search-pages").toFile()
        val empty = LocalSessionEventLog(directory.resolve("empty.events.jsonl"), json)
        val matching = LocalSessionEventLog(directory.resolve("match.events.jsonl"), json)
        try {
            matching.append("user/message", buildJsonObject {
                put("content", "unique-search-needle")
            })
            val all = (0 until 130).map { index ->
                LocalSessionSummary(
                    id = "s" + index.toString().padStart(3, '0'),
                    title = "会话", updatedAt = index.toLong(), projectId = "project-a",
                )
            } + LocalSessionSummary(
                id = "s065-secret", title = "其他项目", updatedAt = 1L, projectId = "project-b",
            )
            val searched = mutableListOf<String>()
            val coordinator = LocalSessionAccessCoordinator(
                summaries = { all },
                currentSessionId = { "s000" },
                currentScope = { LocalSessionAccessScope(projectId = "project-a", lineageId = "root-a") },
                activeScope = { null },
                eventLogFor = { id ->
                    searched += id
                    if (id == "s110" || id == "s065-secret") matching else empty
                },
            )
            val first = coordinator.search("unique-search-needle")
            assertTrue(first.contains("next_cursor=s063"))
            assertTrue(first.contains("本页未找到"))
            val second = coordinator.search("unique-search-needle", cursor = "s063")
            assertTrue(second.contains("会话 s110"))
            assertTrue(second.contains("next_cursor=s127"))
            val third = coordinator.search("unique-search-needle", cursor = "s127")
            assertTrue(!third.contains("next_cursor="))
            assertTrue(searched.size == 130)
            assertTrue("不能查看其它项目", "s065-secret" !in searched)
        } finally {
            empty.close()
            matching.close()
            directory.deleteRecursively()
        }
    }

}
