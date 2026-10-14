package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.PersonaGalleryStory
import com.labteto.dshmobile.local.chat.RelationshipDynamics
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FluidDesignInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun workQuestionRequiresConfirmationAndKeepsSelectionOnFailure() {
        val received = mutableListOf<String>()
        compose.setContent { DshTheme {
            QuestionDialog("Choose a route", listOf("Route A", "Route B"),
                onAnswer = { received += it; received.size > 1 }, onDismiss = {},
                requireConfirmation = true)
        } }
        compose.onNodeWithText("Choose a route").assertIsDisplayed()
        compose.onNodeWithText("Route A").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), received) }
        compose.onNodeWithText("提交回答").performClick()
        compose.onNodeWithText("提交未成功，选择已保留，请重试").assertIsDisplayed()
        compose.onNodeWithText("提交回答").performClick()
        compose.runOnIdle { assertEquals(listOf("Route A", "Route A"), received) }
    }

    @Test fun selectedProfileSectionDoesNotShowPreviousStoryMemoryAfterSwitch() {
        val selected = mutableStateOf(2)
        val story = mutableStateOf(PersonaGalleryStory("old", chatState = ChatCharacterState(
            dynamics = RelationshipDynamics(sharedObjects = listOf("Promise from the old story")),
        )))
        compose.setContent { DshTheme {
            Column {
                FluidSectionNavigation(listOf("Relation", "Events", "Memory", "Settings"),
                    selected.value, { selected.value = it }, tag = "profileTestSection")
                FluidPersonaStorySection(story.value, selected.value)
            }
        } }
        compose.onNodeWithText("Promise from the old story").assertIsDisplayed()
        compose.runOnIdle { story.value = PersonaGalleryStory("fresh") }
        compose.onNodeWithText("Promise from the old story").assertDoesNotExist()
        compose.onNodeWithTag("profileTestSection:2").assertIsSelected()
        compose.onNodeWithTag("profileTestSection:0").performClick()
        compose.onNodeWithTag("profileTestSection:0").assertIsSelected()
    }
    @Test fun messageMediaActionCarriesTextAndDismissesWithoutRegenerating() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requests = mutableListOf<Pair<String, LocalMediaKind>>()
        var regenerationCount = 0
        val message = LocalHarnessMessage("source", "assistant", "Describe a rainy street", createdAt = 1)
        compose.setContent { DshTheme {
            LocalMessageRow(message, chatMode = true, groupMode = false, canEdit = false,
                canRegenerate = true, canSelectVariant = false, branchInfo = null,
                onEdit = {}, onSelectVariant = { _, _ -> false },
                onRegenerate = { regenerationCount++; true },
                onCreateMedia = { source, kind -> requests += source.content to kind })
        } }
        compose.onNodeWithTag("messageActionSurface:source").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.runOnIdle { assertEquals(emptyList<Pair<String, LocalMediaKind>>(), requests) }
        compose.onNodeWithText(context.getString(R.string.fluid_message_media_video)).performClick()
        compose.onNodeWithText(context.getString(R.string.fluid_message_actions)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("Describe a rainy street" to LocalMediaKind.VIDEO), requests)
            assertEquals(0, regenerationCount)
        }
    }

}
