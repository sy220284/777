package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
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
}
