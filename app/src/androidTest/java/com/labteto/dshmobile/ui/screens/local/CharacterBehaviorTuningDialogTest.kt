package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.CharacterBehaviorTuning
import com.labteto.dshmobile.local.chat.CharacterEvolutionState
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class CharacterBehaviorTuningDialogTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun advancedParametersStayHiddenUntilRequestedAndRemainReachable() {
        compose.setContent {
            DshTheme {
                CharacterBehaviorTuningDialog(
                    personaName = "角色",
                    portraitPath = "",
                    relationshipState = "熟悉",
                    mood = "平静",
                    evolution = CharacterEvolutionState(),
                    initial = CharacterBehaviorTuning(),
                    onSave = { Result.success(Unit) },
                    onDismiss = {},
                )
            }
        }

        val evolution = context.getString(R.string.local_character_tuning_evolution)
        val afterglow = context.getString(R.string.local_character_tuning_afterglow)
        val advanced = context.getString(R.string.local_character_tuning_advanced)

        compose.onNodeWithText(evolution).assertDoesNotExist()
        compose.onNodeWithText(afterglow).assertDoesNotExist()

        compose.onNodeWithText(advanced)
            .performScrollTo()
            .performClick()

        compose.onNodeWithText(evolution).assertExists()
        compose.onNodeWithText(afterglow).assertExists()
    }
}
