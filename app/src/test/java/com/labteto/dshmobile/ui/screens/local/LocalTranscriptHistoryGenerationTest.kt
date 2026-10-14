package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.uicontract.TranscriptUiPort
import com.labteto.dshmobile.uicontract.UiTranscriptCursor
import com.labteto.dshmobile.uicontract.UiTranscriptPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTranscriptHistoryGenerationTest {
    @Test fun timelineRewriteRefreshCannotBeOverwrittenByAnOlderRead() = runTest {
        val old = CompletableDeferred<UiTranscriptPage>()
        val newer = CompletableDeferred<UiTranscriptPage>()
        var calls = 0
        val port = object : TranscriptUiPort {
            override suspend fun transcriptPage(sessionId: String, cursor: UiTranscriptCursor?, limit: Int): UiTranscriptPage =
                if (calls++ == 0) old.await() else newer.await()
        }
        val controller = LocalTranscriptHistoryController(port, { "s" }, { emptyList() }, this)
        val first = async { controller.prepare("s") }
        runCurrent()
        controller.refreshAfterTimelineRewrite()
        runCurrent()
        newer.complete(UiTranscriptPage(listOf(LocalHarnessMessage("new", "user", "new", createdAt = 2)), null))
        runCurrent()
        old.complete(UiTranscriptPage(listOf(LocalHarnessMessage("old", "user", "old", createdAt = 1)), null))
        first.await()
        assertEquals(listOf("new"), controller.history.value.olderMessages.map { it.id })
    }
}
