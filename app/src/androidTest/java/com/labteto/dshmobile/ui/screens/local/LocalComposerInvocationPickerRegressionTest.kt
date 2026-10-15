package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UiInvocationOption
import com.labteto.dshmobile.uicontract.UiInvocationRef
import com.labteto.dshmobile.uicontract.UiInvocationKind
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalComposerInvocationPickerRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val github = UiInvocationRef(UiInvocationKind.CONNECTOR, "github")
    private val figma = UiInvocationRef(UiInvocationKind.CONNECTOR, "figma")
    private val review = UiInvocationRef(UiInvocationKind.SKILL, "code-review")
    private val options = listOf(
        UiInvocationOption(github, "GitHub", "已连接"),
        UiInvocationOption(figma, "Figma", "已连接"),
        UiInvocationOption(review, "代码审查", "只读审查"),
    )

    @Test
    fun selectingConnectorUpdatesPinnedChipAndRemovingItDoesNotHidePicker() {
        var selected = listOf(github)
        compose.setContent {
            var pins by remember { mutableStateOf(selected) }
            DshTheme {
                LocalComposerInvocationPicker(options, pins, { value ->
                    pins = if (value in pins) pins - value else pins + value
                    selected = pins
                })
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.composer_invocation_add))
            .performClick()
        compose.onNodeWithText("Figma").performClick()
        compose.runOnIdle { assertEquals(listOf(github, figma), selected) }
        Espresso.pressBack() // Dismiss the multi-select popup before tapping its chips.
        compose.onNodeWithContentDescription(context.getString(R.string.composer_invocation_remove, "Figma"))
            .performClick()
        compose.runOnIdle { assertEquals(listOf(github), selected) }
        compose.onNodeWithContentDescription(context.getString(R.string.composer_invocation_add))
            .assertIsDisplayed()
    }

    @Test
    fun disconnectedPinsStayRemovableButCannotBeActivated() {
        var selected = listOf(figma)
        compose.setContent {
            var pins by remember { mutableStateOf(selected) }
            DshTheme {
                LocalComposerInvocationPicker(
                    options = listOf(UiInvocationOption(
                        figma, "Figma", "未连接", available = false,
                    )),
                    pinned = pins,
                    onToggle = { pins = pins - it; selected = pins },
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.composer_invocation_remove, "Figma"))
            .performClick()
        compose.runOnIdle { assertEquals(emptyList<UiInvocationRef>(), selected) }
    }
}
