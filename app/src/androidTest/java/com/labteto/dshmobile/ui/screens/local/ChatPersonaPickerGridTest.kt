package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatPersonaPickerGridTest {
    @get:Rule val compose = createComposeRule()

    private fun entries(count: Int) = (0 until count).map { index ->
        PersonaGalleryEntry(
            id = "gallery-$index",
            persona = PersonaProfile(id = "persona-$index", name = "测试人物$index"),
        )
    }

    @Test
    fun savedCharactersUsePortraitTilesWithoutVisibleNameCards() {
        var selectedId: String? = null
        var dismissCount = 0
        compose.setContent {
            DshTheme {
                ChatPersonaPickerDialog(
                    entries = entries(3),
                    currentPersona = PersonaProfile(),
                    currentGalleryId = null,
                    canSwitchPersona = true,
                    onSelect = { selectedId = it; true },
                    onEditCurrent = {},
                    onDismiss = { dismissCount++ },
                )
            }
        }

        compose.onNodeWithTag("personaPickerAvatarGrid").assertExists()
        compose.onNodeWithText("测试人物0").assertDoesNotExist()
        compose.onNodeWithTag("personaPickerAvatar_gallery-0").performClick()
        compose.runOnIdle {
            assertEquals("gallery-0", selectedId)
            assertEquals(1, dismissCount)
        }
    }

    @Test
    fun largeGalleryScrollsToLastPortrait() {
        var selectedId: String? = null
        compose.setContent {
            DshTheme {
                ChatPersonaPickerDialog(
                    entries = entries(24),
                    currentPersona = PersonaProfile(),
                    currentGalleryId = null,
                    canSwitchPersona = true,
                    onSelect = { selectedId = it; true },
                    onEditCurrent = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("personaPickerAvatarGrid").performScrollToIndex(23)
        compose.onNodeWithTag("personaPickerAvatar_gallery-23").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("gallery-23", selectedId) }
    }

    @Test
    fun existingConversationDisablesCharacterSwitch() {
        compose.setContent {
            DshTheme {
                ChatPersonaPickerDialog(
                    entries = entries(2),
                    currentPersona = PersonaProfile(),
                    currentGalleryId = null,
                    canSwitchPersona = false,
                    onSelect = { error("不应该切换人物") },
                    onEditCurrent = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("personaPickerAvatar_gallery-0").assertIsNotEnabled()
    }
}
