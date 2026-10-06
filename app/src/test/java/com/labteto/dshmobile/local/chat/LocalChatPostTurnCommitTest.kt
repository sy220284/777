package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.toLocalChatProjectionState

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalChatPostTurnCommitTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun log() = LocalSessionEventLog(File(temporary.root, "events.jsonl"), json)
    private fun store() = ChatDiaryStore(File(temporary.root, "diary"), json)
    private fun state() = LocalHarnessState(sessionId = "s", usageMode = LocalUsageMode.CHAT,
        chat = LocalChatState(chatContext = ChatContextState(processedThroughSequence = 42L)))
        .toLocalChatProjectionState()
    private fun diary() = ChatDiaryWriteRequest(
        subjectKey = "gallery:a", personaName = "阿青",
        delta = ChatDiaryDelta(event = "用户答应周末和我一起去海边看日落", feeling = "我很期待", importance = 5),
        turnSignificance = "MAJOR", sourceMode = ChatDiarySourceMode.DIRECT,
        sourceSessionId = "s", sourceUserMessageIds = listOf("u"),
        sourceAssistantMessageIds = listOf("a"), evidenceText = "用户答应周末和我一起去海边看日落", generation = 1L,
    )

    @Test fun fullStateRecoversWithoutSnapshotOrBranchAlternatives() {
        val log = log()
        try {
            appendChatPostTurnCommit(log, state(), null)
            val recovered = projectChatSessionControls(LocalHarnessSession(id = "s"), log.pageAfter(-1, 100))
            assertEquals(42L, recovered.chatContext.processedThroughSequence)
        } finally { log.close() }
    }

    @Test fun crashAfterDiaryWriteBeforeMarkerDoesNotDuplicateRevision() {
        val log = log()
        val store = store()
        try {
            val event = appendChatPostTurnCommit(log, state(), diary())
            store.record(diary().copy(projectionId = "s:${event.sequence}"))
            recoverChatPostTurnProjections(log, store)
            recoverChatPostTurnProjections(log, store)
            val entries = store.listActive("gallery:a")
            assertEquals(1, entries.size)
            assertEquals(1, entries.single().revisions.size)
        } finally { log.close() }
    }

    @Test fun rewrittenSourcesCannotResurrectDiaryOnRecovery() {
        val log = log()
        val store = store()
        try {
            appendChatPostTurnCommit(log, state(), diary())
            log.append("chat/active-transcript", buildJsonObject {
                put("_dsh_timeline_rewrite_projection", buildJsonObject {
                    put("discardedMessageIds", JsonArray(listOf(JsonPrimitive("a"))))
                })
            })
            recoverChatPostTurnProjections(log, store)
            assertTrue(store.listActive("gallery:a").isEmpty())
        } finally { log.close() }
    }

    @Test fun emptyRecipeTailAdvancesMarkerWithoutRepeatedWrites() {
        val log = log()
        try {
            log.append("diagnostic", buildJsonObject { put("status", "ok") })
            recoverChatPostTurnProjections(log, store())
            val sequence = log.latestSequence()
            recoverChatPostTurnProjections(log, store())
            assertEquals(sequence, log.latestSequence())
        } finally { log.close() }
    }
}
