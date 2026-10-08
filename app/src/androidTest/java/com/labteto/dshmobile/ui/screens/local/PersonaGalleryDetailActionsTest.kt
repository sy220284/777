package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryStory
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PersonaGalleryDetailActionsTest {
    @get:Rule val compose = createComposeRule()

    private val entry = PersonaGalleryEntry(
        id = "detail-actions-persona",
        persona = PersonaProfile(id = "detail-actions-profile", name = "布局测试人物"),
        stories = listOf(PersonaGalleryStory(id = "detail-actions-story", title = "已有故事")),
    )
    private var deletionCount = 0
    private var saves = 0

    private fun openDetail(pendingProgress: Boolean = false) {
        compose.setContent {
            DshTheme {
                PersonaGalleryScreen(
                    entries = listOf(entry),
                    presets = emptyList(),
                    currentPersona = entry.persona,
                    currentGalleryId = entry.id,
                    currentGalleryStoryId = entry.stories.single().id,
                    currentHasUnsavedChanges = pendingProgress,
                    currentSessionId = "detail-actions-session",
                    canSave = true,
                    onSaveCurrent = { _, _, _, _ -> saves++; Result.success(entry) },
                    onEditNotes = { _, _, _ -> Result.success(Unit) },
                    onRenameStory = { _, _, _ -> Result.success(Unit) },
                    onInspect = { _, _ -> Result.success(PersonaInspectionResult()) },
                    onApplySuggestions = { _, _ -> Result.success(entry) },
                    onDelete = { deletionCount++; Result.success(Unit) },
                    onDeleteStory = { _, _ -> deletionCount++; Result.success(Unit) },
                    onDeleteHistoryMessage = { _, _, _ -> Result.success(Unit) },
                    onExport = { _, _ -> Result.failure(IllegalStateException("测试不执行导出")) },
                    onImport = { _, _, _ -> Result.success(entry) },
                    onInstallPreset = { Result.success(entry) },
                    onSetPortrait = { _, _ -> Result.success(entry) },
                    onRemovePortrait = { Result.success(entry) },
                    onStart = { _, _, _ -> },
                    onCreate = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(entry.persona.name).performClick()
        compose.onNodeWithText("继续这条故事").assertIsDisplayed()
    }

    @Test
    fun lowFrequencyActionsMoveToSheetAndSystemBackKeepsDetail() {
        openDetail()
        compose.onNodeWithText("重命名故事").assertDoesNotExist()
        compose.onNodeWithText("人物检查").assertDoesNotExist()
        compose.onNodeWithText("导出人物卡").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("重命名故事").assertIsDisplayed()
        compose.onNodeWithText("人物检查").performScrollTo().assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("人物详情").assertIsDisplayed()
        compose.onNodeWithText("继续这条故事").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun cancellingRenameReleasesMainActionsAndDeleteRequiresConfirmation() {
        openDetail()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("重命名故事").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("未保存名称")
        pressBack()
        compose.onNodeWithText("继续这条故事").assertIsEnabled()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("删除这条故事").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, deletionCount) }
        compose.onNodeWithText("确认删除").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, deletionCount) }
    }

    @Test
    fun unsavedNotesBlockStoryStartAndProgressSaveUsesExistingCallback() {
        openDetail(pendingProgress = true)
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("保存本次对话进展").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithText("添加").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("必须保存的剧情提要")
        compose.onNodeWithText("继续这条故事").assertIsNotEnabled()
        compose.onNodeWithText("用这个人物开启新故事").assertIsNotEnabled()
    }
}
