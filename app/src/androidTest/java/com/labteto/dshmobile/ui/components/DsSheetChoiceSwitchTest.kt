package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DsSheetChoiceSwitchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowAndSwitchVisualEachToggleExactlyOnce() {
        val checked = mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            DshTheme {
                DsSheetChoiceRow(title = "推理", switchChecked = checked.value,
                    modifier = Modifier.testTag("reasoning-row"),
                    onClick = { clicks++; checked.value = !checked.value })
            }
        }
        val row = compose.onNodeWithTag("reasoning-row")
        row.assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, clicks) }
        row.performTouchInput { click(Offset(center.x * 1.8f, center.y)) }.assertIsOff()
        compose.runOnIdle { assertEquals(2, clicks) }
    }
}
