package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
import com.labteto.dshmobile.ui.theme.DshTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The image's only save/share entrance is the top-right anchored overflow. */
class MediaImageDetailInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullscreenImageUsesTopRightActionsWithoutLegacyButtons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "media-preview-interaction.png")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        try {
            compose.setContent {
                DshTheme {
                    MediaImageDetailDialog(file, remoteUrl = null, onDismiss = {})
                }
            }
            compose.onNodeWithTag("syFullscreenMediaOverlay").assertIsDisplayed()
            compose.onNodeWithText("保存").assertDoesNotExist()
            compose.onNodeWithText("分享").assertDoesNotExist()
            compose.onNodeWithText("还原大小").assertDoesNotExist()
            compose.onNodeWithText("关闭").assertDoesNotExist()

            compose.onNodeWithTag("mediaImageActions").assertIsDisplayed().performClick()
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.onNodeWithText("分享").assertIsDisplayed()
        } finally {
            file.delete()
        }
    }

    @Test fun tappingPictureDoesNotDismissButBackgroundDoes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "media-preview-tap-test.png")
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        var dismissed = false
        try {
            compose.setContent {
                DshTheme {
                    MediaImageDetailDialog(file, remoteUrl = null, onDismiss = { dismissed = true })
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("mediaImageContent").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mediaImageContent").performClick()
            compose.runOnIdle { assertTrue(!dismissed) }
            compose.onNodeWithTag("syFullscreenMediaOverlay")
                .performTouchInput { click(Offset(4f, 4f)) }
            compose.runOnIdle { assertTrue(dismissed) }
        } finally {
            file.delete()
        }
    }

    @Test fun messageOverflowContainsOnlySecondaryActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var createdDocuments = 0
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = com.labteto.dshmobile.local.session.LocalHarnessMessage(
                        id = "reply", role = "assistant", content = "测试回复", createdAt = 1L,
                    ),
                    chatMode = false,
                    groupMode = false,
                    canEdit = false,
                    canRegenerate = true,
                    canSelectVariant = false,
                    branchInfo = null,
                    onEdit = {},
                    onSelectVariant = { _, _ -> true },
                    onRegenerate = { true },
                    onCreateDocument = { createdDocuments++ },
                )
            }
        }
        compose.onNodeWithTag("messageActionToolbar:reply").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            context.getString(com.labteto.dshmobile.R.string.local_regenerate_reply),
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            context.getString(com.labteto.dshmobile.R.string.fluid_message_actions),
        ).performClick()
        compose.onNodeWithText("为此回复生成交互文档").assertIsDisplayed()
        compose.onNodeWithText("重新生成回复").assertDoesNotExist()
        compose.onNodeWithText("复制").assertDoesNotExist()
        compose.onNodeWithText("为此回复生成交互文档").performClick()
        compose.runOnIdle { assertTrue(createdDocuments == 1) }
    }


    @Test fun longPressImageRequestsConfirmedDeletionWithoutOpeningPreview() {
        var opened = 0
        var deleted = ""
        compose.setContent {
            DshTheme {
                MediaDeleteTarget(id = "image-one", onDelete = { deleted = it }) { requestDelete ->
                    MediaInlinePicture(file = null, remoteUrl = null,
                        onOpen = { opened++ }, onLongPress = requestDelete)
                }
            }
        }
        compose.onNodeWithTag("mediaInlinePicture").performTouchInput { longClick() }
        compose.onNodeWithText("确认删除").assertIsDisplayed()
        compose.runOnIdle { assertTrue(opened == 0 && deleted.isEmpty()) }
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5_000) { deleted == "image-one" }
        compose.runOnIdle { assertTrue(opened == 0) }
    }

    @Test fun videoAndAudioCardsKeepLongPressDeletionButPendingCannotDelete() {
        var deleted = ""
        compose.setContent {
            DshTheme {
                androidx.compose.foundation.layout.Column {
                    MediaDeleteTarget(id = "audio-one", onDelete = { deleted = it }) { request ->
                        MediaMessageCard(title = "语音成果", subtitle = "",
                            modifier = androidx.compose.ui.Modifier.testTag("audioDeletion"),
                            onLongPress = request) {
                            androidx.compose.material3.Text("音频播放")
                        }
                    }
                    MediaDeleteTarget(id = "still-generating", enabled = false,
                        onDelete = { deleted = it }) { request ->
                        MediaMessageCard(title = "生成中", subtitle = "",
                            modifier = androidx.compose.ui.Modifier.testTag("pendingDeletion"),
                            onLongPress = request) {
                            androidx.compose.material3.Text("正在处理")
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("pendingDeletion").performTouchInput { longClick() }
        compose.onNodeWithText("确认删除").assertDoesNotExist()
        compose.onNodeWithTag("audioDeletion").performTouchInput { longClick() }
        compose.onNodeWithText("确认删除").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithTag("audioDeletion").performTouchInput { longClick() }
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5_000) { deleted == "audio-one" }
    }

    @Test fun zoomedOverlayDoesNotDismissOnBackgroundTap() {
        var dismissed = false
        compose.setContent {
            DshTheme {
                com.labteto.dshmobile.ui.shenyan.SyFullscreenMediaOverlay(
                    onDismiss = { dismissed = true },
                    dismissOnBackgroundTap = false,
                ) {}
            }
        }
        compose.onNodeWithTag("syFullscreenMediaOverlay")
            .performTouchInput { click(Offset(4f, 4f)) }
        compose.runOnIdle { assertTrue(!dismissed) }
    }

    @Test fun remoteOnlyImageShowsShareWithoutPretendingLocalSave() {
        compose.setContent {
            DshTheme {
                MediaImageDetailDialog(file = null, remoteUrl = "https://example.com:8443/image.png", onDismiss = {})
            }
        }
        compose.onNodeWithTag("mediaImageActions").performClick()
        compose.onNodeWithText("分享链接").assertIsDisplayed()
        compose.onNodeWithText("保存").assertDoesNotExist()
    }
}
