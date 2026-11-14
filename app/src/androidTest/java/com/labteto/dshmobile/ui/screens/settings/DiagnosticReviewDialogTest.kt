package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiagnosticReviewDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frozenPreviewIsShownBeforeAnyExportOrShare() {
        var exported = 0
        var shared = 0
        val report = "应用版本：777-v\n日志完整性：正常\n敏感内容已脱敏"
        compose.setContent {
            DshTheme {
                DiagnosticReviewDialog(
                    report = report, busy = false, onDismiss = {},
                    onExport = { exported++ }, onShare = { shared++ },
                )
            }
        }
        compose.onNodeWithText(report).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, exported); assertEquals(0, shared) }
        compose.onNodeWithTag("diagnostic-reviewed-export").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, exported); assertEquals(0, shared) }
        compose.onNodeWithTag("diagnostic-reviewed-share").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, exported); assertEquals(1, shared) }
    }

    @Test fun cancellingReviewSkipsBothSavingAndSharing() {
        var dismissed = 0
        var exported = 0
        var shared = 0
        compose.setContent {
            DshTheme {
                DiagnosticReviewDialog(
                    report = "脱敏诊断报告", busy = false,
                    onDismiss = { dismissed++ }, onExport = { exported++ },
                    onShare = { shared++ },
                )
            }
        }
        compose.onNodeWithTag("diagnostic-reviewed-cancel").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, exported)
            assertEquals(0, shared)
        }
    }
}
