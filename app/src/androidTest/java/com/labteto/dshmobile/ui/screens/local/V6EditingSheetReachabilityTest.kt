package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.labteto.dshmobile.ui.screens.tasks.AutomationTimePickerSheet
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

/** Regression: formerly non-scrollable editing sheets must not trap actions under the IME. */
class V6EditingSheetReachabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun groupAnnouncementUsesConstrainedScrollableBody() {
        compose.setContent {
            DshTheme {
                GroupAnnouncementSheet(
                    sessionId = "session-1",
                    announcement = "Previous announcement",
                    enabled = true,
                    onSave = { Result.success(Unit) },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("bottomSheetScrollBody").assertExists()
    }

    @Test fun taskTimePickerUsesConstrainedScrollableBody() {
        compose.setContent {
            DshTheme {
                AutomationTimePickerSheet(
                    initialMinuteOfDay = 11 * 60,
                    onPicked = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("bottomSheetScrollBody").assertExists()
    }
}
