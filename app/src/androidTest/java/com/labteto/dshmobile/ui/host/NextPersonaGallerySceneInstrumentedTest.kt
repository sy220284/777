package com.labteto.dshmobile.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.next.NextPersonaGalleryScene
import com.labteto.dshmobile.uipack.next.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real Next page + owner-directed archive reads/selection; no app-domain gallery duplication. */
class NextPersonaGallerySceneInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val title = "人物：青岚"
    private val story = "窗前的约定"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Archive : PersonaGalleryUiPort {
        var readCount = 0
        override suspend fun read(): UiPersonaGalleryReadReceipt {
            readCount++
            return UiPersonaGalleryReadReceipt.Available(listOf(
                UiPersonaGalleryEntry("gallery-A", "人物：青岚", listOf(
                    UiPersonaGalleryStory("story-B", "窗前的约定", "", 8, "初识")
                ))
            ))
        }
        override suspend fun submit(command: PersonaGalleryUiCommand): UiCommandReceipt =
            error("archive writes are not required for story selection")
    }

    private class Selection(
        canStart: Boolean = true,
        identity: UiSessionIdentity? = UiSessionIdentity("session-1", "activation-1", 3L, null, null),
        var outcome: UiCommandReceipt = UiCommandReceipt.Accepted,
    ) : PersonaSelectionUiPort {
        private val state = UiPersonaSelectionSnapshot(identity, canStart, canStart, null)
        override val snapshots = MutableStateFlow(state)
        override fun snapshot(): UiPersonaSelectionSnapshot = state
        val requests = mutableListOf<PersonaSelectionUiCommand>()
        override fun submit(command: PersonaSelectionUiCommand): UiCommandReceipt {
            requests += command
            return outcome
        }
    }

    private fun render(archive: Archive, selection: Selection, onStoryOpened: () -> Unit) {
        compose.setContent {
            MaterialTheme {
                NextPersonaGalleryScene(
                    gallery = archive, selection = selection,
                    onBack = {}, onStoryOpened = onStoryOpened,
                )
            }
        }
    }

    @Test fun archivedStoryUsesExactOwnerGalleryAndStoryIds() {
        val archive = Archive()
        val selection = Selection()
        var opened = 0
        render(archive, selection) { opened++ }
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(story).assertExists()
        compose.onNodeWithText(context.getString(R.string.next_gallery_continue_story)).performClick()
        compose.runOnIdle {
            assertEquals(1, archive.readCount)
            assertEquals(1, opened)
            val command = selection.requests.single() as PersonaSelectionUiCommand.StartStory
            assertEquals("gallery-A", command.galleryId)
            assertEquals("story-B", command.storyId)
            assertFalse(command.freshStory)
            assertEquals("session-1", command.identity.sessionId)
        }
    }

    @Test fun unboundSessionCannotStartArchivedStory() {
        val selection = Selection(canStart = false, identity = null)
        var opened = 0
        render(Archive(), selection) { opened++ }
        compose.onNodeWithText(story).assertExists()
        compose.onNodeWithText(context.getString(R.string.next_gallery_continue_story)).performClick()
        compose.runOnIdle {
            assertTrue(selection.requests.isEmpty())
            assertEquals(0, opened)
        }
    }

    @Test fun rejectedOwnerReceiptCannotNavigateAsIfStoryStarted() {
        val selection = Selection(outcome = UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY))
        var opened = 0
        render(Archive(), selection) { opened++ }
        compose.onNodeWithText(story).assertExists()
        compose.onNodeWithText(context.getString(R.string.next_gallery_continue_story)).performClick()
        compose.onNodeWithText(context.getString(R.string.next_gallery_action_rejected)).assertExists()
        compose.runOnIdle {
            assertEquals(1, selection.requests.size)
            assertEquals(0, opened)
        }
    }
}
