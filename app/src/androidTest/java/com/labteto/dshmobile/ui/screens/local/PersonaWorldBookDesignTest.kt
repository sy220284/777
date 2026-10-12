package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.labteto.dshmobile.local.chat.PersonaLoreEntry
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PersonaWorldBookDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectingAnEntryEditsOnlyItsExistingLoreDraft() {
        val draft = mutableStateOf(listOf(
            PersonaLoreEntry(id = "a", title = "阁楼", content = "旧屋"),
            PersonaLoreEntry(id = "b", title = "图书馆", content = "旧书"),
        ))
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PersonaWorldBookEditor(draft.value) { draft.value = it }
                }
            }
        }

        compose.onNodeWithTag("personaLoreSelect:1").performScrollTo().performClick()
        compose.onNodeWithTag("personaLoreContent").performScrollTo()
            .performTextReplacement("新的图书馆背景")
        compose.runOnIdle {
            assertEquals("旧屋", draft.value[0].content)
            assertEquals("新的图书馆背景", draft.value[1].content)
        }
    }


    @Test
    fun changingCharacterResetsEditorSelectionToItsOwnFirstLoreEntry() {
        val draft = mutableStateOf(listOf(
            PersonaLoreEntry(id = "first", title = "甲一"),
            PersonaLoreEntry(id = "second", title = "甲二"),
        ))
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PersonaWorldBookEditor(draft.value) { draft.value = it }
                }
            }
        }
        compose.onNodeWithTag("personaLoreSelect:1").performScrollTo().performClick()
        compose.runOnIdle {
            draft.value = listOf(
                PersonaLoreEntry(id = "other-first", title = "乙一"),
                PersonaLoreEntry(id = "other-second", title = "乙二"),
            )
        }
        compose.onNodeWithTag("personaLoreContent").performScrollTo().performTextReplacement("新人物的独立背景")
        compose.runOnIdle {
            assertEquals("新人物的独立背景", draft.value[0].content)
            assertEquals("", draft.value[1].content)
        }
    }

    @Test
    fun newEntryUsesTheSameDraftOwnerAndOpensItsEditor() {
        val draft = mutableStateOf(listOf(PersonaLoreEntry(id = "a", title = "阁楼")))
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PersonaWorldBookEditor(draft.value) { draft.value = it }
                }
            }
        }

        compose.onNodeWithTag("personaLoreAdd").performScrollTo().performClick()
        compose.onNodeWithTag("personaLoreTitle").performScrollTo().performTextInput("小巷")
        compose.runOnIdle {
            assertEquals(2, draft.value.size)
            assertEquals("阁楼", draft.value.first().title)
            assertEquals("小巷", draft.value.last().title)
        }
    }
}
