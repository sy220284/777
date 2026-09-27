package com.labteto.dshmobile.local.memory

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = MemoryStore(temporary.root, Json)
    private fun all(store: MemoryStore) = store.listActive(MemoryScope.values().toSet(), "project", "lineage")

    @Test fun writesSurviveRestartAndLeaveNoTemporaryFile() {
        val record = store().remember("remember apples", MemoryScope.GLOBAL)
        assertEquals(record, all(store()).single())
        assertTrue(File(temporary.root, "memories.json.bak").isFile)
        assertFalse(File(temporary.root, "memories.json.tmp").exists())
    }

    @Test fun firstWriteBackupRecoversCorruptedPrimary() {
        val memoryStore = store()
        val record = memoryStore.remember("remember apples", MemoryScope.GLOBAL)
        File(temporary.root, "memories.json").writeText("broken")
        assertEquals(record, all(memoryStore).single())
        assertTrue(temporary.root.listFiles()!!.any { it.name.startsWith("memories.corrupt-") })
        assertEquals(record, all(memoryStore).single())
    }

    @Test fun missingPrimaryRecoversBackupAndCanWriteAgain() {
        val record = store().remember("remember apples", MemoryScope.GLOBAL)
        assertTrue(File(temporary.root, "memories.json").delete())
        assertEquals(record, all(store()).single())
        store().remember("remember oranges", MemoryScope.GLOBAL)
        assertEquals(2, all(store()).size)
    }

    @Test fun bothCorruptFilesAllowFreshWriteWithoutRevivingBadData() {
        store().remember("remember apples", MemoryScope.GLOBAL)
        File(temporary.root, "memories.json").writeText("broken primary")
        File(temporary.root, "memories.json.bak").writeText("broken backup")
        assertTrue(all(store()).isEmpty())
        val record = store().remember("remember oranges", MemoryScope.GLOBAL)
        assertEquals(record, all(store()).single())
    }

    @Test fun lineageWriteRestartRecallIsIsolatedFromIndependentConversations() {
        val record = store().remember("apples project decision", MemoryScope.LINEAGE, lineageId = "lineage")
        val restarted = store()
        assertEquals(listOf(record), restarted.search("apples", setOf(MemoryScope.LINEAGE), null, "lineage"))
        assertTrue(restarted.search("apples", MemoryScope.values().toSet(), null, "other").isEmpty())
        assertTrue(restarted.search("apples", setOf(MemoryScope.GLOBAL), null, "lineage").isEmpty())
    }

    @Test fun searchCanExcludeRelationshipMemories() {
        val memoryStore = store()
        memoryStore.remember(
            "关系对象：林晚｜女朋友",
            MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_FACT,
        )
        val ordinary = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            kind = MemoryKind.PREFERENCE,
        )

        val allowedKinds = MemoryKind.values()
            .filterNot {
                it in setOf(
                    MemoryKind.RELATIONSHIP_FACT,
                    MemoryKind.RELATIONSHIP_STATE,
                    MemoryKind.RELATIONSHIP_PREFERENCE,
                )
            }
            .toSet()

        assertTrue(
            memoryStore.search(
                query = "林晚女朋友",
                allowedScopes = setOf(MemoryScope.GLOBAL),
                projectId = null,
                lineageId = null,
                allowedKinds = allowedKinds,
            ).isEmpty(),
        )
        assertEquals(
            listOf(ordinary),
            memoryStore.search(
                query = "简洁回复",
                allowedScopes = setOf(MemoryScope.GLOBAL),
                projectId = null,
                lineageId = null,
                allowedKinds = allowedKinds,
            ),
        )
    }

    @Test fun identicalRelationshipTextForDifferentCharactersDoesNotDeduplicate() {
        val memoryStore = store()
        val first = memoryStore.remember(
            "关系状态：我和同名角色｜在一起",
            MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            subjectKey = "gallery:one",
        )
        val second = memoryStore.remember(
            "关系状态：我和同名角色｜在一起",
            MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            subjectKey = "gallery:two",
        )

        assertNotEquals(first.id, second.id)
        assertEquals(setOf("gallery:one", "gallery:two"), all(memoryStore).mapNotNull { it.subjectKey }.toSet())
        assertEquals(setOf("gallery:one", "gallery:two"), all(store()).mapNotNull { it.subjectKey }.toSet())
    }

    @Test fun replacementDoesNotResurrectSupersededMemoryAfterRestart() {
        val old = store().remember("apples old", MemoryScope.GLOBAL)
        val replacement = store().remember("apples new", MemoryScope.GLOBAL, replaceIds = setOf(old.id))
        assertEquals(listOf(replacement), all(store()))
    }

    @Test fun duplicateRefreshMovesProvenanceToNewestSession() {
        val memoryStore = store()
        val first = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            sourceSessionId = "old-session",
        )
        val refreshed = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            sourceSessionId = "new-session",
        )
        assertEquals(first.id, refreshed.id)
        assertEquals("new-session", all(memoryStore).single().sourceSessionId)
    }

    @Test fun deletingConversationSourceDetachesProvenanceButKeepsMemory() {
        val memoryStore = store()
        val sourced = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            sourceSessionId = "deleted-session",
        )
        memoryStore.remember(
            "另一个事实",
            MemoryScope.GLOBAL,
            sourceSessionId = "kept-session",
        )

        assertEquals(1, memoryStore.detachSourceSessions(setOf("deleted-session")))
        val remaining = all(store())
        assertEquals(2, remaining.size)
        assertNull(remaining.single { it.id == sourced.id }.sourceSessionId)
        assertEquals("kept-session", remaining.single { it.id != sourced.id }.sourceSessionId)
    }

    @Test fun updateAndForgetPersistAcrossRestart() {
        val original = store().remember("remember apples", MemoryScope.GLOBAL, importance = 50)
        val updated = store().update(
            id = original.id,
            content = "remember oranges",
            importance = 90,
            pinned = true,
        )
        assertEquals(original.id, updated.id)
        assertEquals("remember oranges", all(store()).single().content)
        assertEquals(90, all(store()).single().importance)
        assertTrue(all(store()).single().pinned)

        assertTrue(store().forget(original.id))
        assertTrue(all(store()).isEmpty())
        assertFalse(store().forget(original.id))
    }

    @Test fun timelineRollbackDropsNewMemoriesAndReactivatesSupersededValidState() {
        val memoryStore = store()
        val valid = memoryStore.remember(
            "关系状态：我和阿青｜熟悉",
            MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            sourceSessionId = "older-session",
        )
        val discarded = memoryStore.remember(
            "关系状态：我和阿青｜在一起",
            MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            sourceSessionId = "edited-session",
            replaceIds = setOf(valid.id),
        )

        assertEquals(discarded.id, all(memoryStore).single().id)
        assertEquals(
            1,
            memoryStore.rollbackSourceSessionFrom(
                sourceSessionId = "edited-session",
                createdAtInclusive = discarded.createdAt,
            ),
        )

        val restored = all(store())
        assertEquals(1, restored.size)
        assertEquals(valid.id, restored.single().id)
        assertEquals("关系状态：我和阿青｜熟悉", restored.single().content)
    }


    @Test fun messageProvenanceRollsBackOnlyDiscardedTurnEvenWithBroadTimeBoundary() {
        val memoryStore = store()
        val kept = memoryStore.remember(
            "用户喜欢安静散步",
            MemoryScope.GLOBAL,
            sourceSessionId = "session-a",
            sourceMessageId = "u-kept",
        )
        val discarded = memoryStore.remember(
            "用户喜欢夜游",
            MemoryScope.GLOBAL,
            sourceSessionId = "session-a",
            sourceMessageId = "u-discarded",
        )

        assertEquals(
            1,
            memoryStore.rollbackSourceSessionFrom(
                sourceSessionId = "session-a",
                createdAtInclusive = 0L,
                discardedMessageIds = setOf("u-discarded"),
            ),
        )

        val remaining = all(memoryStore)
        assertEquals(listOf(kept.id), remaining.map { it.id })
        assertTrue(remaining.none { it.id == discarded.id })
    }

    @Test fun duplicateFactSurvivesWhenOnlyOneOfItsSourceTurnsIsDiscarded() {
        val memoryStore = store()
        val first = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            sourceSessionId = "session-a",
            sourceMessageId = "u1",
        )
        val repeated = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            sourceSessionId = "session-a",
            sourceMessageId = "u2",
        )

        assertEquals(first.id, repeated.id)
        assertEquals(
            setOf("u1", "u2"),
            all(memoryStore).single().sourceMessages.map { it.messageId }.toSet(),
        )

        assertEquals(
            0,
            memoryStore.rollbackSourceSessionFrom(
                sourceSessionId = "session-a",
                createdAtInclusive = 0L,
                discardedMessageIds = setOf("u2"),
            ),
        )
        val remaining = all(memoryStore).single()
        assertEquals(first.id, remaining.id)
        assertEquals(listOf("u1"), remaining.sourceMessages.map { it.messageId })
    }


    @Test fun laterExactConfirmationDoesNotEraseOlderUnboundFact() {
        val memoryStore = store()
        val legacy = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            sourceSessionId = "legacy-session",
        )
        val repeated = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            sourceSessionId = "session-a",
            sourceMessageId = "u2",
        )

        assertEquals(legacy.id, repeated.id)
        assertTrue(all(memoryStore).single().hasUnboundSource)

        assertEquals(
            0,
            memoryStore.rollbackSourceSessionFrom(
                sourceSessionId = "session-a",
                createdAtInclusive = 0L,
                discardedMessageIds = setOf("u2"),
            ),
        )
        val remaining = all(memoryStore).single()
        assertEquals(legacy.id, remaining.id)
        assertTrue(remaining.sourceMessages.isEmpty())
        assertTrue(remaining.hasUnboundSource)
    }

}
