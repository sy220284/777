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

    @Test fun bothCorruptFilesFailClosedAndPreserveDamagedData() {
        store().remember("remember apples", MemoryScope.GLOBAL)
        File(temporary.root, "memories.json").writeText("broken primary")
        File(temporary.root, "memories.json.bak").writeText("broken backup")

        val failure = runCatching { all(store()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("主文件与备份均无法读取"))
        assertTrue(temporary.root.listFiles().orEmpty().any { it.name.startsWith("memories.corrupt-") })
    }

    @Test fun tornJournalTailIsQuarantinedAndValidMutationsSurviveRestart() {
        val memoryStore = store()
        val first = memoryStore.remember("remember apples", MemoryScope.GLOBAL)
        val second = memoryStore.remember("remember oranges", MemoryScope.GLOBAL)
        val journal = File(temporary.root, "memories.wal.jsonl")
        assertTrue(journal.isFile)
        journal.appendText("{broken-tail")

        val restarted = store()
        assertEquals(setOf(first.id, second.id), all(restarted).map { it.id }.toSet())
        assertTrue(
            temporary.root.listFiles().orEmpty().any {
                it.name.startsWith("memories.wal.corrupt-")
            },
        )
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


    @Test fun laterUnboundConfirmationProtectsEarlierExactFactFromRollback() {
        val memoryStore = store()
        val exact = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            sourceSessionId = "session-a",
            sourceMessageId = "u1",
        )
        val repeated = memoryStore.remember(
            "用户喜欢简洁回复",
            MemoryScope.GLOBAL,
            sourceSessionId = "legacy-session",
        )

        assertEquals(exact.id, repeated.id)
        assertTrue(all(memoryStore).single().hasUnboundSource)

        assertEquals(
            0,
            memoryStore.rollbackSourceSessionFrom(
                sourceSessionId = "session-a",
                createdAtInclusive = 0L,
                discardedMessageIds = setOf("u1"),
            ),
        )
        val remaining = all(memoryStore).single()
        assertEquals(exact.id, remaining.id)
        assertTrue(remaining.hasUnboundSource)
        assertTrue(remaining.sourceMessages.isEmpty())
    }

    @Test fun searchFiltersRelationshipOwnerBeforeResultLimit() {
        val memoryStore = store()
        repeat(6) { index ->
            memoryStore.remember(
                content = "关系状态：我和其他角色$index｜在一起",
                scope = MemoryScope.GLOBAL,
                kind = MemoryKind.RELATIONSHIP_STATE,
                subjectKey = "gallery:other-$index",
                importance = 100,
            )
        }
        val target = memoryStore.remember(
            content = "关系状态：我和目标角色｜在一起",
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            subjectKey = "gallery:target",
            importance = 60,
        )

        val result = memoryStore.search(
            query = "关系状态 在一起",
            allowedScopes = setOf(MemoryScope.GLOBAL),
            projectId = null,
            lineageId = null,
            allowedKinds = setOf(MemoryKind.RELATIONSHIP_STATE),
            maxItems = 1,
            recordFilter = { it.subjectKey == "gallery:target" },
        )

        assertEquals(listOf(target.id), result.map { it.id })
    }

    @Test fun storageCompactionKeepsActiveRollbackChainBeforeDiscardedNoise() {
        fun record(
            id: String,
            active: Boolean,
            supersededBy: String? = null,
            pinned: Boolean = false,
            importance: Int = 50,
            updatedAt: Long,
        ) = MemoryRecord(
            id = id,
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            content = id,
            importance = importance,
            pinned = pinned,
            active = active,
            supersededBy = supersededBy,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

        val records = listOf(
            record("forgotten", active = false, updatedAt = 100L),
            record("old-valid", active = false, supersededBy = "current", updatedAt = 200L),
            record("current", active = true, pinned = true, importance = 90, updatedAt = 300L),
            record("other-active", active = true, importance = 40, updatedAt = 400L),
        )

        val compacted = compactMemoryRecords(records, maxRecords = 3)

        assertEquals(setOf("old-valid", "current", "other-active"), compacted.map { it.id }.toSet())
        assertFalse(compacted.any { it.id == "forgotten" })
    }

    @Test fun storageCompactionReservesCapacityForNearestRollbackPredecessor() {
        fun record(
            id: String,
            active: Boolean,
            supersededBy: String? = null,
            importance: Int,
        ) = MemoryRecord(
            id = id,
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.FACT,
            content = id,
            active = active,
            supersededBy = supersededBy,
            importance = importance,
            createdAt = importance.toLong(),
            updatedAt = importance.toLong(),
        )

        val compacted = compactMemoryRecords(
            records = listOf(
                record("old", active = false, supersededBy = "current", importance = 1),
                record("current", active = true, importance = 100),
                record("other", active = true, importance = 90),
            ),
            maxRecords = 2,
        )

        assertEquals(setOf("old", "current"), compacted.map { it.id }.toSet())
    }

    @Test fun storageCompactionPrioritizesPinnedAndImportantActiveRootsWhenOverCapacity() {
        fun active(id: String, pinned: Boolean, importance: Int, updatedAt: Long) = MemoryRecord(
            id = id,
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.FACT,
            content = id,
            pinned = pinned,
            importance = importance,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

        val compacted = compactMemoryRecords(
            records = listOf(
                active("low-new", pinned = false, importance = 10, updatedAt = 400L),
                active("important", pinned = false, importance = 95, updatedAt = 200L),
                active("pinned", pinned = true, importance = 20, updatedAt = 100L),
            ),
            maxRecords = 2,
        )

        assertEquals(setOf("pinned", "important"), compacted.map { it.id }.toSet())
    }


    @Test fun storageCompactionNeverLetsOneLongChainEvictOtherActiveMemories() {
        fun record(
            id: String,
            active: Boolean,
            supersededBy: String? = null,
            importance: Int = 50,
            updatedAt: Long,
        ) = MemoryRecord(
            id = id,
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.FACT,
            content = id,
            importance = importance,
            active = active,
            supersededBy = supersededBy,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

        val records = listOf(
            record("chain-1", active = false, supersededBy = "chain-2", updatedAt = 1L),
            record("chain-2", active = false, supersededBy = "chain-3", updatedAt = 2L),
            record("chain-3", active = false, supersededBy = "root-a", updatedAt = 3L),
            record("root-a", active = true, importance = 100, updatedAt = 4L),
            record("root-b", active = true, importance = 10, updatedAt = 5L),
            record("root-c", active = true, importance = 10, updatedAt = 6L),
        )

        val compacted = compactMemoryRecords(records, maxRecords = 4)

        assertTrue(compacted.any { it.id == "root-a" })
        assertTrue(compacted.any { it.id == "root-b" })
        assertTrue(compacted.any { it.id == "root-c" })
        assertTrue(compacted.any { it.id == "chain-3" })
        assertFalse(compacted.any { it.id == "chain-1" })
    }

    @Test fun protectedNewMemorySurvivesCapacityCompaction() {
        fun active(id: String, importance: Int, updatedAt: Long) = MemoryRecord(
            id = id,
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.FACT,
            content = id,
            importance = importance,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

        val compacted = compactMemoryRecords(
            records = listOf(
                active("old-high", importance = 100, updatedAt = 1L),
                active("old-mid", importance = 80, updatedAt = 2L),
                active("new-low", importance = 1, updatedAt = 3L),
            ),
            maxRecords = 2,
            protectedIds = setOf("new-low"),
        )

        assertTrue(compacted.any { it.id == "new-low" })
        assertEquals(2, compacted.size)
    }


    @Test fun explicitEpisodicRecallKeepsLowLexicalFactInCandidateSet() {
        val memoryStore = store()
        val episode = memoryStore.remember(
            content = "关系对象稳定信息：她平时喜欢海边看日落",
            scope = MemoryScope.LINEAGE,
            kind = MemoryKind.RELATIONSHIP_FACT,
            lineageId = "lineage",
            importance = 80,
        )
        memoryStore.remember(
            content = "关系状态：我和她｜在一起",
            scope = MemoryScope.LINEAGE,
            kind = MemoryKind.RELATIONSHIP_STATE,
            lineageId = "lineage",
            importance = 100,
        )

        val recalled = memoryStore.search(
            query = "你还记得我们第一次出去那天吗",
            allowedScopes = setOf(MemoryScope.LINEAGE),
            projectId = null,
            lineageId = "lineage",
            allowedKinds = setOf(
                MemoryKind.RELATIONSHIP_FACT,
                MemoryKind.RELATIONSHIP_STATE,
            ),
            maxItems = 1,
        )

        assertEquals(listOf(episode.id), recalled.map { it.id })
    }

    @Test fun consolidationMergesFormattingVariantsAndPreservesAllSources() {
        val memoryStore = store()
        memoryStore.remember(
            content = "用户喜欢简洁回复",
            scope = MemoryScope.GLOBAL,
            sourceSessionId = "s1",
            sourceMessageId = "u1",
            importance = 70,
        )
        memoryStore.remember(
            content = "用户喜欢简洁回复。",
            scope = MemoryScope.GLOBAL,
            sourceSessionId = "s2",
            sourceMessageId = "u2",
            importance = 90,
            pinned = true,
        )

        assertEquals(1, memoryStore.consolidateIfDue(force = true))
        val active = all(memoryStore)
        assertEquals(1, active.size)
        assertTrue(active.single().pinned)
        assertEquals(90, active.single().importance)
        assertEquals(setOf("u1", "u2"), active.single().sourceMessages.map { it.messageId }.toSet())
    }

    @Test fun consolidationNeverMergesSameTextAcrossCharacterSubjects() {
        val memoryStore = store()
        memoryStore.remember(
            content = "关系状态：我和同名角色｜在一起",
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            subjectKey = "gallery:one",
        )
        memoryStore.remember(
            content = "关系状态：我和同名角色｜在一起。",
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            subjectKey = "gallery:two",
        )

        assertEquals(0, memoryStore.consolidateIfDue(force = true))
        assertEquals(setOf("gallery:one", "gallery:two"), all(memoryStore).mapNotNull { it.subjectKey }.toSet())
    }


    @Test fun consolidatedSourcesStillRollbackIndependentlyByMessage() {
        val memoryStore = store()
        memoryStore.remember(
            content = "用户喜欢简洁回复",
            scope = MemoryScope.GLOBAL,
            sourceSessionId = "s",
            sourceMessageId = "u1",
        )
        memoryStore.remember(
            content = "用户喜欢简洁回复。",
            scope = MemoryScope.GLOBAL,
            sourceSessionId = "s",
            sourceMessageId = "u2",
        )
        assertEquals(1, memoryStore.consolidateIfDue(force = true))

        assertEquals(
            0,
            memoryStore.rollbackSourceSessionFrom(
                sourceSessionId = "s",
                createdAtInclusive = Long.MIN_VALUE,
                discardedMessageIds = setOf("u2"),
            ),
        )
        var active = all(memoryStore)
        assertEquals(1, active.size)
        assertEquals(listOf("u1"), active.single().sourceMessages.map { it.messageId })

        assertEquals(
            1,
            memoryStore.rollbackSourceSessionFrom(
                sourceSessionId = "s",
                createdAtInclusive = Long.MIN_VALUE,
                discardedMessageIds = setOf("u1"),
            ),
        )
        active = all(memoryStore)
        assertTrue(active.isEmpty())
    }


    @Test fun broadRecallDoesNotLoseLowLexicalEpisodeBehindHighImportanceDistractors() {
        val memoryStore = store()
        val episode = memoryStore.remember(
            content = "关系对象稳定信息：她平时喜欢海边看日落",
            scope = MemoryScope.LINEAGE,
            kind = MemoryKind.RELATIONSHIP_FACT,
            lineageId = "lineage",
            importance = 80,
        )
        repeat(24) { index ->
            memoryStore.remember(
                content = "关系状态：我和她｜历史状态$index",
                scope = MemoryScope.LINEAGE,
                kind = MemoryKind.RELATIONSHIP_STATE,
                lineageId = "lineage",
                importance = 100,
            )
        }

        val recalled = memoryStore.search(
            query = "你还记得我们第一次出去那天吗",
            allowedScopes = setOf(MemoryScope.LINEAGE),
            projectId = null,
            lineageId = "lineage",
            allowedKinds = setOf(
                MemoryKind.RELATIONSHIP_FACT,
                MemoryKind.RELATIONSHIP_STATE,
            ),
            maxItems = 1,
        )

        assertEquals(listOf(episode.id), recalled.map { it.id })
    }


    @Test fun boundedSourceHistoryMarksOlderOmittedProvenanceAsUnbound() {
        val memoryStore = store()
        repeat(20) { index ->
            memoryStore.remember(
                content = "用户喜欢简洁回复",
                scope = MemoryScope.GLOBAL,
                sourceSessionId = "s",
                sourceMessageId = "u$index",
            )
        }

        var active = all(memoryStore)
        assertEquals(1, active.size)
        assertEquals(16, active.single().sourceMessages.size)
        assertTrue(active.single().hasUnboundSource)

        val trackedIds = active.single().sourceMessages.map { it.messageId }.toSet()
        assertEquals(
            0,
            memoryStore.rollbackSourceSessionFrom(
                sourceSessionId = "s",
                createdAtInclusive = Long.MIN_VALUE,
                discardedMessageIds = trackedIds,
            ),
        )
        active = all(memoryStore)
        assertEquals(1, active.size)
        assertTrue(active.single().sourceMessages.isEmpty())
        assertTrue(active.single().hasUnboundSource)
    }

}
