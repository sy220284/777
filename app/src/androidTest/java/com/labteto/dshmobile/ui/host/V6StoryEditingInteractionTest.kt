package com.labteto.dshmobile.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.v6.V6PersonaArchivePart
import com.labteto.dshmobile.uipack.v6.V6PersonaArchiveScene
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Recompose the real native scene; catches keyed empty drafts and hidden departure feedback. */
class V6StoryEditingInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val identity = UiSessionIdentity("chat", "active", 1L, null, null)
    private class Gallery : PersonaGalleryUiPort {
        @Volatile var story = UiPersonaGalleryStory("story", "第一段", "初始备注", 3, "相识")
        override suspend fun read() = UiPersonaGalleryReadReceipt.Available(
            listOf(UiPersonaGalleryEntry("person", "测试人物", listOf(story))))
        override suspend fun submit(command: PersonaGalleryUiCommand): UiCommandReceipt {
            if (command is PersonaGalleryUiCommand.RenameStory) story = story.copy(title = command.title)
            return UiCommandReceipt.Accepted
        }
    }
    private fun show(gallery: Gallery, failSave: Boolean = false) {
        val selection = object : PersonaSelectionUiPort {
            override val snapshots = MutableStateFlow(UiPersonaSelectionSnapshot(identity, true, true, null))
            override fun snapshot() = snapshots.value
            override fun submit(command: PersonaSelectionUiCommand) = UiCommandReceipt.Accepted
        }
        val stories = object : PersonaStoryUiPort {
            override suspend fun submit(command: PersonaStoryUiCommand): UiPersonaStoryReceipt {
                if (failSave) return UiPersonaStoryReceipt.Failed("暂时无法保存")
                if (command is PersonaStoryUiCommand.SaveDetails)
                    gallery.story = gallery.story.copy(notes = command.notes, storyStage = command.storyStage)
                return UiPersonaStoryReceipt.Saved(UiStorySessionSync.APPLIED)
            }
        }
        compose.setContent {
            MaterialTheme { V6PersonaArchiveScene(gallery, selection, stories, deletion = null,
                part = V6PersonaArchivePart.GALLERY, onBack = {}) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("测试人物").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("测试人物").performClick()
        compose.onNodeWithText("第一段").performScrollTo().performClick()
    }

    @Test fun selectingStoryLoadsItsFieldsAndCancelDeparturePreservesEdits() {
        show(Gallery())
        compose.onNodeWithText("初始备注").assertExists()
        compose.onNodeWithText("故事备注").performScrollTo().performTextReplacement("尚未保存的备注")
        compose.onNodeWithText("切换故事").performScrollTo().performClick()
        compose.onNodeWithText("还有未保存的修改").assertExists()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("尚未保存的备注").assertExists()
    }

    @Test fun saveAndLeaveConfirmsOwnerDataBeforeSwitchingStory() {
        val gallery = Gallery()
        show(gallery)
        compose.onNodeWithText("故事备注").performScrollTo().performTextReplacement("已保存的新备注")
        compose.onNodeWithText("切换故事").performScrollTo().performClick()
        compose.onNodeWithText("保存并离开").performClick()
        compose.waitUntil(5_000) { gallery.story.notes == "已保存的新备注" }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("还有未保存的修改").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("第一段").performScrollTo().performClick()
        compose.onNodeWithText("已保存的新备注").assertExists()
        assertEquals("已保存的新备注", gallery.story.notes)
    }

    @Test fun failedSaveKeepsErrorAndDraftInsideDepartureDialog() {
        show(Gallery(), failSave = true)
        compose.onNodeWithText("故事备注").performScrollTo().performTextReplacement("不能丢的内容")
        compose.onNodeWithText("切换故事").performScrollTo().performClick()
        compose.onNodeWithText("保存并离开").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("暂时无法保存").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("暂时无法保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("不能丢的内容").assertExists()
    }
}
