package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.PersonaGalleryStory
import com.labteto.dshmobile.local.chat.RelationshipDynamics
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FluidDesignInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun decisionNeedsExplicitSubmissionAndFailureKeepsAnswerForRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val attempts = mutableListOf<String>()
        compose.setContent { DshTheme {
            FluidDecisionPanel("decision-1", "Choose a route", listOf("Route A", "Route B"),
                onAnswer = { attempts += it; attempts.size > 1 }, onCancel = {})
        } }
        compose.onNodeWithText("Route A").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), attempts) }
        compose.onNodeWithTag("workDecisionCustom").performTextInput("Use the existing endpoint")
        compose.onNodeWithTag("workDecisionContinue").performClick()
        compose.onNodeWithText(context.getString(R.string.fluid_decision_failed)).assertIsDisplayed()
        compose.onNodeWithTag("workDecisionCustom").assertTextEquals("Use the existing endpoint")
        compose.onNodeWithTag("workDecisionContinue").performClick()
        compose.runOnIdle { assertEquals(listOf("Use the existing endpoint", "Use the existing endpoint"), attempts) }
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
}
