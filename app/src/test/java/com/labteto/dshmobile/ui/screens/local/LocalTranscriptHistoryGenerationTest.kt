package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.uicontract.TranscriptUiPort
import com.labteto.dshmobile.uicontract.UiTranscriptCursor
import com.labteto.dshmobile.uicontract.UiTranscriptPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTranscriptHistoryGenerationTest {
    @Test fun timelineRewriteRefreshCannotBeOverwrittenByAnOlderRead() = runTest {
        val old = CompletableDeferred<UiTranscriptPage>()
        val newer = CompletableDeferred<UiTranscriptPage>()
        var calls = 0
        val firstReadStarted = CompletableDeferred<Unit>()
        val refreshReadStarted = CompletableDeferred<Unit>()
        val port = object : TranscriptUiPort {
            override suspend fun transcriptPage(sessionId: String, cursor: UiTranscriptCursor?, limit: Int): UiTranscriptPage =
                if (calls++ == 0) {
                    firstReadStarted.complete(Unit)
                    old.await()
                } else {
                    refreshReadStarted.complete(Unit)
                    newer.await()
                }
        }
        val controller = LocalTranscriptHistoryController(port, { "s" }, { emptyList() }, this)
        val first = async { controller.prepare("s") }
        runCurrent()
        // Dispatchers.IO is intentionally not controlled by runCurrent(). Wait until the
        // first owner read has actually started before invalidating its generation.
        firstReadStarted.await()
        controller.refreshAfterTimelineRewrite()
        runCurrent()
        refreshReadStarted.await()
        newer.complete(UiTranscriptPage(listOf(LocalHarnessMessage("new", "user", "new", createdAt = 2)), null))
        controller.history.first { state -> state.olderMessages.map { it.id } == listOf("new") }
        old.complete(UiTranscriptPage(listOf(LocalHarnessMessage("old", "user", "old", createdAt = 1)), null))
        first.await()
        assertEquals(listOf("new"), controller.history.value.olderMessages.map { it.id })
    }
}
