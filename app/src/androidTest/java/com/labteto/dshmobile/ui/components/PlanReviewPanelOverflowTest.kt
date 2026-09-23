package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Regression for long plan reviews on short phone viewports.
 *
 * The decision row must remain on-screen while only the plan body scrolls. If the body is allowed
 * to claim a fixed height before the action row is measured, the row is laid out below the clipped
 * card and the harness wait becomes impossible to approve or decline from the phone.
 */
class PlanReviewPanelOverflowTest {

    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun longApprovalReasonKeepsAllowAndRejectVisible() {
        var allowed = 0
        var rejected = 0
        val reason = (1..80).joinToString("\n") { index ->
            "Approval reason line $index with enough detail to overflow a short phone viewport."
        }

        compose.setContent {
            DshTheme {
                Box(Modifier.width(360.dp).height(240.dp)) {
                    ApprovalPanel(
                        toolName = "test_tool",
                        reason = reason,
                        onAllow = { allowed++ },
                        onReject = { rejected++ },
                    )
                }
            }
        }

        val allow = context.getString(R.string.approval_allow_once)
        val reject = context.getString(R.string.approval_reject)

        compose.onNodeWithText(allow).assertIsDisplayed().performClick()
        compose.onNodeWithText(reject).assertIsDisplayed().performClick()
        compose.onNode(hasScrollAction()).assertExists()

        assertEquals(1, allowed)
        assertEquals(1, rejected)
    }

    @Test
    fun longPlanKeepsDecisionActionsVisibleAndBodyScrollable() {
        var approved = 0
        var declined = 0
        var discussed = 0
        val review = PlanReview(
            id = "plan-1",
            question = "Proceed?",
            plan = (1..80).joinToString("\n\n") { index ->
                "Step $index — enough plan text to force the review body beyond a phone viewport."
            },
            approve = AskUserQuestionOption("Approve"),
            decline = AskUserQuestionOption("Decline"),
        )

        compose.setContent {
            DshTheme {
                // Deliberately shorter than the old 220dp body plus header, padding and actions.
                Box(Modifier.width(360.dp).height(240.dp)) {
                    PlanReviewPanel(
                        review = review,
                        busy = false,
                        onApprove = { approved++ },
                        onDecline = { declined++ },
                        onDiscuss = { discussed++ },
                    )
                }
            }
        }

        val approve = context.getString(R.string.plan_review_approve)
        val decline = context.getString(R.string.plan_review_decline)
        val discuss = context.getString(R.string.plan_review_discuss)

        compose.onNodeWithText(approve).assertIsDisplayed().performClick()
        compose.onNodeWithText(decline).assertIsDisplayed().performClick()
        compose.onNodeWithText(discuss).assertIsDisplayed().performClick()
        compose.onNode(hasScrollAction()).assertExists()

        assertEquals(1, approved)
        assertEquals(1, declined)
        assertEquals(1, discussed)
    }
}
