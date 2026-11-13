package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSessionModelSelectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }

    private fun eventLog(sessionId: String): LocalSessionEventLog =
        LocalSessionEventLog(File(temporary.root, "$sessionId.events.jsonl"), json, sessionId = sessionId)

    @Test fun differentConversationsKeepIndependentSelectionsAcrossReloads() {
        val first = eventLog("chat-one")
        val second = eventLog("work-two")
        assertNull(first.selectedLocalModelProfileId())
        assertNull(second.selectedLocalModelProfileId())

        first.recordLocalModelSelection("profile-a")
        second.recordLocalModelSelection("profile-b")
        first.recordLocalModelSelection("profile-c")

        assertEquals("profile-c", eventLog("chat-one").selectedLocalModelProfileId())
        assertEquals("profile-b", eventLog("work-two").selectedLocalModelProfileId())
    }

    @Test fun selectingAProfileChangesOnlyTheNextSnapshotAndPreservesWorkerConfig() {
        val first = LocalModelProfile("a", "deepseek-flash", "https://api.deepseek.com")
        val second = LocalModelProfile("b", "MiniMax-M3", "https://api.minimaxi.com/v1")
        val current = LocalModelState(
            model = first.model,
            baseUrl = first.baseUrl,
            modelSelection = LocalModelSelectionState(
                profiles = listOf(first, second), activeProfileId = first.id,
                workerProfileId = first.id,
            ),
        )
        val chosen = current.forSessionModel(second)
        assertEquals(first.id, current.modelSelection.activeProfileId)
        assertEquals(second.id, chosen.modelSelection.activeProfileId)
        assertEquals(second.model, chosen.model)
        assertEquals(second.baseUrl, chosen.baseUrl)
        assertEquals(first.id, chosen.modelSelection.workerProfileId)
        assertTrue(chosen.configured)
    }

    @Test fun persistedSelectionIsAppliedAtTurnBoundaryNotToCapturedSnapshot() {
        val old = LocalModelProfile("old", "deepseek-flash", "https://api.deepseek.com")
        val next = LocalModelProfile("next", "MiniMax-M3", "https://api.minimaxi.com/v1")
        val duringRequest = LocalModelState(
            model = old.model,
            baseUrl = old.baseUrl,
            modelSelection = LocalModelSelectionState(listOf(old, next), old.id),
        )
        val log = eventLog("same-session")
        log.recordLocalModelSelection(next.id)
        val nextTurn = duringRequest.forPersistedSessionModel(log.selectedLocalModelProfileId())
        assertEquals(old.id, duringRequest.modelSelection.activeProfileId)
        assertEquals(next.id, nextTurn.modelSelection.activeProfileId)
        assertEquals(old.model, duringRequest.model)
        assertEquals(next.model, nextTurn.model)
    }
}
