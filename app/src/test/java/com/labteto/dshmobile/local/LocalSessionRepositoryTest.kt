package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.VersionedSessionStore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalSessionRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun deletionCannotBeUndoneByQueuedSnapshots() = runTest {
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, {})
        repository.enqueue(LocalHarnessSession(id = "gone", title = "old"))
        runCurrent()
        repository.enqueue(LocalHarnessSession(id = "gone", title = "queued"))
        repository.delete("gone")
        runCurrent()
        assertEquals(null, repository.read("gone"))
        assertTrue(repository.summaries().none { it.id == "gone" })
        repository.enqueue(LocalHarnessSession(id = "gone", title = "late"))
        runCurrent()
        assertEquals(null, repository.read("gone"))
    }

    @Test fun readPrefersNewestQueuedSnapshotBeforeWriterRuns() = runTest {
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, {})
        repository.enqueue(
            LocalHarnessSession(
                id = "group",
                usageMode = LocalUsageMode.CHAT,
                groupChat = LocalGroupChatState(
                    mode = LocalChatMode.GROUP,
                    announcement = "雨夜客栈，众人刚刚收到同一封匿名信。",
                ),
            ),
        )

        assertEquals(
            "雨夜客栈，众人刚刚收到同一封匿名信。",
            repository.read("group")!!.groupChat.announcement,
        )
    }

    @Test fun explicitWriteIsDurableBeforeReturnAndSupersedesQueuedSnapshot() = runTest {
        val failures = mutableListOf<Throwable>()
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        repository.enqueue(
            LocalHarnessSession(
                id = "group",
                usageMode = LocalUsageMode.CHAT,
                groupChat = LocalGroupChatState(
                    mode = LocalChatMode.GROUP,
                    announcement = "旧公告",
                ),
            ),
        )
        repository.writeNow(
            LocalHarnessSession(
                id = "group",
                usageMode = LocalUsageMode.CHAT,
                groupChat = LocalGroupChatState(
                    mode = LocalChatMode.GROUP,
                    announcement = "新公告",
                ),
            ),
        )
        runCurrent()

        val reopened = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        assertEquals("新公告", reopened.read("group")!!.groupChat.announcement)
        assertTrue(failures.isEmpty())
    }

    @Test fun coalescesSnapshotsWithoutDroppingOtherSessions() = runTest {
        val failures = mutableListOf<Throwable>()
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        repository.enqueue(LocalHarnessSession(id = "first", title = "old"))
        repository.enqueue(
            LocalHarnessSession(
                id = "second",
                title = "other",
                usageMode = LocalUsageMode.CHAT,
                messages = listOf(LocalHarnessMessage("m1", "user", "hello", createdAt = 1L)),
            ),
        )
        repository.enqueue(LocalHarnessSession(id = "first", title = "new"))
        runCurrent()
        val reopened = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        assertEquals("new", reopened.read("first")!!.title)
        assertEquals("other", reopened.read("second")!!.title)
        assertEquals(LocalUsageMode.CHAT, reopened.read("second")!!.usageMode)
        assertEquals(setOf("first", "second"), reopened.summaries().map { it.id }.toSet())
        val summaries = reopened.summaries().associateBy { it.id }
        assertEquals(LocalUsageMode.WORK, summaries.getValue("first").usageMode)
        assertEquals(LocalUsageMode.CHAT, summaries.getValue("second").usageMode)
        assertTrue(summaries.getValue("first").blank)
        assertFalse(summaries.getValue("second").blank)
        assertTrue(failures.isEmpty())
    }

    @Test fun summaryIndexServesColdListWithoutOpeningMainSessionDocument() = runTest {
        val failures = mutableListOf<Throwable>()
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        repository.writeNow(
            LocalHarnessSession(
                id = "indexed",
                title = "索引标题",
                updatedAt = 10L,
                projectId = "project-a",
                lineageId = "lineage-a",
            ),
        )
        val main = java.io.File(temporary.root, "indexed.json")
        val generation = main.lastModified()
        assertTrue(java.io.File(temporary.root, ".summaries/indexed.summary").isFile)

        main.writeText("{broken")
        assertTrue(main.setLastModified(generation))

        val reopened = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        val summary = reopened.summaries().single { it.id == "indexed" }

        assertEquals("索引标题", summary.title)
        assertEquals("project-a", summary.projectId)
        assertEquals("lineage-a", summary.lineageId)
        assertEquals("{broken", main.readText())
        assertTrue(failures.isEmpty())
    }

    @Test fun legacySummarySidecarRebuildsGroupMemberCountFromAuthoritativeSession() = runTest {
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, {})
        repository.writeNow(
            LocalHarnessSession(
                id = "legacy-group",
                title = "旧群聊",
                updatedAt = 10L,
                usageMode = LocalUsageMode.CHAT,
                groupChat = LocalGroupChatState(
                    mode = LocalChatMode.GROUP,
                    members = listOf(
                        LocalGroupChatMember("a", "persona-a", "甲"),
                        LocalGroupChatMember("b", "persona-b", "乙"),
                    ),
                ),
            ),
        )

        val main = java.io.File(temporary.root, "legacy-group.json")
        val summaryFile = java.io.File(temporary.root, ".summaries/legacy-group.summary")
        val sourceModifiedAt = main.lastModified()
        summaryFile.writeText(
            """{"id":"legacy-group","title":"旧群聊","updatedAt":10,"usageMode":"CHAT","chatMode":"GROUP","blank":true,"sourceModifiedAt":$sourceModifiedAt}""",
        )

        val reopened = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, {})
        val summary = reopened.summaries().single { it.id == "legacy-group" }

        assertEquals(2, summary.groupMemberCount)
        assertTrue(summary.blank)
        assertTrue(summaryFile.readText().contains("\"version\":2"))
        assertTrue(summaryFile.readText().contains("\"groupMemberCount\":2"))
    }

    @Test fun staleSummarySidecarRebuildsOnlyFromItsAuthoritativeSession() = runTest {
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, {})
        repository.writeNow(LocalHarnessSession(id = "stale", title = "旧标题", updatedAt = 1L))

        val store = VersionedSessionStore(temporary.root, Json)
        val originalGeneration = requireNotNull(store.lastModified("stale"))
        val updated = LocalHarnessSession(
            id = "stale",
            title = "新标题",
            updatedAt = 2L,
            projectId = "project-new",
            lineageId = "lineage-new",
        )
        store.write(
            id = updated.id,
            payload = Json.encodeToJsonElement(LocalHarnessSession.serializer(), updated).jsonObject,
            updatedAt = updated.updatedAt,
        )

        // This fixture represents a different source generation; rapid writes can share one
        // filesystem millisecond, so establish the stale-sidecar condition without sleeping.
        assertTrue(java.io.File(temporary.root, "stale.json").setLastModified(originalGeneration + 1_000L))
        val reopened = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, {})
        val summary = reopened.summaries().single { it.id == "stale" }

        assertEquals("新标题", summary.title)
        assertEquals("project-new", summary.projectId)
        assertEquals("lineage-new", summary.lineageId)
        assertTrue(java.io.File(temporary.root, ".summaries/stale.summary").isFile)
    }

    @Test fun legacySessionWithoutUsageModeDefaultsToWork() {
        val legacy = Json.decodeFromString(
            LocalHarnessSession.serializer(),
            """{"id":"legacy","title":"old","updatedAt":1}""",
        )
        assertEquals(LocalUsageMode.WORK, legacy.usageMode)
        assertEquals(
            com.labteto.dshmobile.local.chat.PersonaProfile.DEFAULT_PERSONA_ID,
            legacy.personaId,
        )
        assertEquals("自然", legacy.chatState.mood)
        assertTrue(legacy.replySuggestions.isEmpty())
    }

    @Test fun boundedTranscriptWindowKeepsSessionSummaryNonBlankWithoutLegacyMessages() = runTest {
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, {})
        repository.enqueue(
            LocalHarnessSession(
                id = "windowed",
                title = "windowed",
                messages = emptyList(),
                transcriptWindow = listOf(
                    LocalHarnessMessage("m1", "user", "hello", createdAt = 1L),
                ),
            ),
        )
        runCurrent()

        assertFalse(repository.summaries().single { it.id == "windowed" }.blank)
    }

    @Test fun failedWriteRetriesTheLatestSnapshotAfterStorageRecovers() = runTest {
        val root = temporary.newFile("blocked-sessions")
        val failures = mutableListOf<Throwable>()
        val repository = LocalSessionRepository(root, Json, backgroundScope, {}, failures::add)
        repository.enqueue(LocalHarnessSession(id = "first", title = "before"))
        runCurrent()
        assertEquals(1, failures.size)

        repository.enqueue(LocalHarnessSession(id = "first", title = "latest"))
        assertTrue(root.delete())
        assertTrue(root.mkdir())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("latest", repository.read("first")!!.title)
    }

    @Test fun summaryCacheTracksFreshWritesAfterInitialLoad() = runTest {
        val failures = mutableListOf<Throwable>()
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        repository.enqueue(LocalHarnessSession(id = "first", title = "before"))
        runCurrent()

        assertEquals("before", repository.summaries().single().title)

        repository.enqueue(
            LocalHarnessSession(
                id = "first",
                title = "after",
                messages = listOf(LocalHarnessMessage("m1", "user", "hello", createdAt = 1L)),
            ),
        )
        runCurrent()

        val summary = repository.summaries().single()
        assertEquals("after", summary.title)
        assertFalse(summary.blank)
        assertTrue(failures.isEmpty())
    }

}
