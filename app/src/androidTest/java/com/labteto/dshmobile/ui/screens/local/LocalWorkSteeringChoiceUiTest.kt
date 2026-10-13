package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class LocalWorkSteeringChoiceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyRunningDraftDoesNotShowControlAndDismissRestoresQueueMode() {
        var draft by mutableStateOf("")
        var dismissed by mutableStateOf(false)
        var steering by mutableStateOf(false)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            DshTheme {
                if (shouldShowWorkSteeringChoice(LocalUsageMode.WORK, true, false, draft.isNotBlank(), dismissed)) {
                    LocalWorkSteeringChoice(
                        selected = steering,
                        onToggle = { steering = !steering },
                        onDismiss = { steering = false; dismissed = true },
                    )
                }
            }
        }
        val label = ctx.getString(R.string.local_work_steer_now)
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(R.string.local_work_next_turn)).assertDoesNotExist()
        compose.runOnIdle { draft = "请调整检查顺序" }
        compose.onNodeWithTag("work-steering-choice").assertExists()
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(ctx.getString(R.string.local_work_steer_boundary)).assertExists()
        compose.onNodeWithContentDescription(ctx.getString(R.string.common_close)).performClick()
        compose.onNodeWithTag("work-steering-choice").assertDoesNotExist()
        compose.runOnIdle { assertFalse(steering) }
    }
}
