package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.shenyan.designsystem.components.SyTextField
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 四种媒体编辑模式与图片比例共用无材质底板的轻量标签选择器。 */
class MediaStudioCompactLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allMediaModesRemainReachableAndSelected() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            DshTheme {
                MediaStudioFlatTabs(
                    labels = listOf("图片", "视频", "配音", "转写"),
                    selected = selected.intValue,
                    onSelect = { selected.intValue = it },
                    tag = "mediaDesignSection",
                )
            }
        }
        for (index in 0..3) {
            compose.onNodeWithTag("mediaDesignSection:$index").assertIsDisplayed().performClick()
            compose.onNodeWithTag("mediaDesignSection:$index").assertIsSelected()
            compose.runOnIdle { assertEquals(index, selected.intValue) }
        }
    }

    @Test fun imageRatiosRemainIndependentlySelectable() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            DshTheme {
                MediaStudioFlatTabs(
                    labels = listOf("1:1", "16:9", "9:16"),
                    selected = selected.intValue,
                    onSelect = { selected.intValue = it },
                    tag = "mediaRatio",
                )
            }
        }
        compose.onNodeWithTag("mediaRatio:2").assertIsDisplayed().performClick()
        compose.onNodeWithTag("mediaRatio:2").assertIsSelected()
        compose.runOnIdle { assertEquals(2, selected.intValue) }
    }

    @Test fun disabledSelectorDoesNotSwitchMode() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            DshTheme {
                MediaStudioFlatTabs(
                    labels = listOf("图片", "视频"),
                    selected = selected.intValue,
                    onSelect = { selected.intValue = it },
                    tag = "disabledMediaMode",
                    enabled = false,
                )
            }
        }
        compose.onNodeWithTag("disabledMediaMode:1").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, selected.intValue) }
    }
    @Test fun expandedPromptShowsMoreRowsAndStillAllowsSubmission() {
        var submitted = 0
        compose.setContent {
            DshTheme {
                SyResponsiveSheet(
                    title = "多模态创作",
                    onDismiss = {},
                    modifier = Modifier.heightIn(max = 520.dp),
                    scrollable = true,
                    scrollBodyHeightFraction = .85f,
                    seamlessBackground = true,
                ) {
                    MediaStudioFlatTabs(
                        labels = listOf("图片", "视频", "配音", "转写"),
                        selected = 0, onSelect = {}, tag = "mediaDesignSection",
                    )
                    SyTextField(
                        value = "女孩站在窗前，晨光照在书桌上。".repeat(25),
                        onValueChange = {},
                        label = { Text("描述想生成的内容") },
                        minLines = 12,
                        maxLines = 20,
                        modifier = Modifier.fillMaxWidth().testTag("mediaStudioPromptField"),
                    )
                    SyButton(
                        text = "生成图片",
                        onClick = { submitted++ },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        compose.onNodeWithTag("mediaStudioPromptField").assertHeightIsAtLeast(200.dp)
        compose.onNodeWithText("生成图片").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun shortViewportKeepsCreationActionReachableWithoutMaterialPlate() {
        var submitted = 0
        compose.setContent {
            DshTheme {
                SyResponsiveSheet(
                    title = "多模态创作",
                    onDismiss = {},
                    modifier = Modifier.heightIn(max = 360.dp),
                    scrollable = true,
                    seamlessBackground = true,
                    dimScrimWhenSeamless = true,
                ) {
                    MediaStudioFlatTabs(
                        labels = listOf("图片", "视频", "配音", "转写"),
                        selected = 1, onSelect = {},
                        tag = "mediaDesignSection",
                    )
                    Text("MiniMax Hailuo 2.3")
                    SyTextField(
                        value = "镜头缓慢推进，夜色下的街道逐渐亮起灯光。".repeat(5),
                        onValueChange = {},
                        label = { Text("描述想生成的内容") },
                        minLines = 4, maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SyButton(
                        text = "开始生成视频",
                        onClick = { submitted++ },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        compose.onNodeWithTag("sySceneBackgroundSurface").assertIsDisplayed()
        compose.onNodeWithTag("mediaDesignSection:1").assertIsSelected()
        compose.onNodeWithText("开始生成视频").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }

}
