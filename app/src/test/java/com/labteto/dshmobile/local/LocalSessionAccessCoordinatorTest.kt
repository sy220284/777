package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.session.LocalSessionAccessCoordinator
import com.labteto.dshmobile.local.session.LocalSessionAccessScope
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.nio.file.Files
import kotlinx.serialization.json.Json
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
}
