package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.labteto.dshmobile.local.chat.ChatDiaryDelta
import com.labteto.dshmobile.local.chat.ChatDiaryEntry
import com.labteto.dshmobile.local.chat.ChatDiarySourceRef
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CharacterDiaryCorrectionRegressionTest {
    @get:Rule val compose = createComposeRule()

    private fun entry() = ChatDiaryEntry(
        id = "event-1",
        subjectKey = "persona:person-1",
        personaName = "阿青",
        event = "周末一起去海边",
        feeling = "很期待",
        sources = listOf(ChatDiarySourceRef(sessionId = "chat-1", userMessageId = "msg-1")),
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun editingDiaryRequiresExplicitSaveAndRefreshesCurrentCharacter() {
        val current = mutableStateOf(listOf(entry()))
        var saved = 0
        compose.setContent {
            DshTheme {
                CharacterDiaryScreen(
                    gallery = emptyList(), currentPersona = PersonaProfile(id = "person-1", name = "阿青"),
                    currentGalleryId = null,
                    loadEntries = { current.value },
                    onCorrectEntry = { subject, id, version, change ->
                        assertEquals("persona:person-1", subject)
                        assertEquals("event-1", id)
                        assertEquals(1L, version)
                        current.value = listOf(entry().copy(event = change.event, updatedAt = 2L))
                        saved++
                        true
                    },
                    onDeactivateEntry = { _, _, _ -> false },
                    onOpenSourceSession = { false },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("diary_edit_event-1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, saved) }
        compose.onNodeWithTag("diary_edit_event").performTextReplacement("周末改去山上")
        compose.onNodeWithTag("diary_save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("周末改去山上").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, saved) }
    }

    @Test
    fun deactivationNeedsConfirmationAndSourceNavigationHasExplicitAction() {
        val current = mutableStateOf(listOf(entry()))
        var deactivated = 0
        var opened = 0
        compose.setContent {
            DshTheme {
                CharacterDiaryScreen(
                    gallery = emptyList(), currentPersona = PersonaProfile(id = "person-1", name = "阿青"),
                    currentGalleryId = null,
                    loadEntries = { current.value },
                    onCorrectEntry = { _, _, _, _ -> false },
                    onDeactivateEntry = { _, id, version ->
                        assertEquals("event-1", id)
                        assertEquals(1L, version)
                        deactivated++
                        current.value = emptyList()
                        true
                    },
                    onOpenSourceSession = { opened++; false },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("diary_source_event-1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithTag("diary_deactivate_event-1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, deactivated) }
        compose.onNodeWithTag("diary_confirm_deactivate").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, deactivated)
            assertTrue(current.value.isEmpty())
        }
    }
}
