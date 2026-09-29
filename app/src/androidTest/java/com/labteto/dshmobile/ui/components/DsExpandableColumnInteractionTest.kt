package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class DsExpandableColumnInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun repeatedToggleSettlesToRequestedVisibility() {
        var visible by mutableStateOf(false)
        compose.setContent {
            DshTheme {
                Column {
                    Button(onClick = { visible = !visible }) {
                        Text("Toggle")
                    }
                    DsExpandableColumn(visible = visible) {
                        Text("Details")
                    }
                }
            }
        }

        val toggle = compose.onNodeWithText("Toggle")
        val details = compose.onNodeWithText("Details")
        details.assertDoesNotExist()

        repeat(4) {
            toggle.performClick()
            compose.waitForIdle()
            details.assertExists()

            toggle.performClick()
            compose.waitForIdle()
            details.assertDoesNotExist()
        }
    }
}
