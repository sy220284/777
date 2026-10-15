package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.local.chat.CharacterFact
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class PersonaFormSimplificationRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun creatingPersonaShowsOnlyIdentityUntilUserAddsAField() {
        compose.setContent {
            DshTheme {
                ChatPersonaDialog(
                    profile = PersonaProfile(name = "", coreIdentity = ""),
                    onSave = { Result.success(Unit) },
                    onAutoFill = { Result.failure(IllegalStateException("not configured")) },
                    onDismiss = {},
                    creatingNew = true,
                )
            }
        }

        compose.onNodeWithText("人物名字").assertExists()
        compose.onNodeWithText("核心身份").assertExists()
        compose.onNodeWithText("稳定性格").assertDoesNotExist()
        compose.onNodeWithText("作品来源／所属系列").assertDoesNotExist()

        compose.onNodeWithText("＋ 添加人物设定").performClick()
        compose.onNodeWithText("稳定性格").performClick()
        compose.onNodeWithText("稳定性格").assertExists()
        compose.onNodeWithText("作品来源／所属系列").assertDoesNotExist()
    }

    @Test
    fun existingPersonaKeepsPopulatedOptionalFieldsButHidesUnusedCategories() {
        val persona = PersonaProfile(
            name = "测试人物",
            coreIdentity = "肩负使命的旅人",
            franchise = "原创世界",
            facts = listOf(CharacterFact(
                id = "history",
                category = "biography",
                content = "在远方长大",
            )),
        )
        compose.setContent {
            DshTheme {
                ChatPersonaDialog(
                    profile = persona,
                    onSave = { Result.success(Unit) },
                    onAutoFill = { Result.failure(IllegalStateException("not configured")) },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("人生经历").assertExists()
        compose.onNodeWithText("在远方长大").assertExists()
        compose.onNodeWithText("作品来源／所属系列").assertExists()
        compose.onNodeWithText("情感烙印").assertDoesNotExist()
    }
}
