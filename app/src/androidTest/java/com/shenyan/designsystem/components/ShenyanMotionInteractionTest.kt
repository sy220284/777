package com.shenyan.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.LocalSyMotionTempo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real Compose clock: streaming updates, live reduced-motion change, and disclosure input. */
class ShenyanMotionInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun entranceMovesIntoPlaceAndStreamingTextDoesNotReplayIt() {
        val text = mutableStateOf("第一段")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                SyEntrance("same-message", Modifier.testTag("message")) { Text(text.value) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val start = compose.onNodeWithTag("message").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val end = compose.onNodeWithTag("message").fetchSemanticsNode().boundsInRoot.top
        assertTrue("消息入场应有上移", start > end)
        compose.runOnIdle { text.value = "第一段继续流式追加" }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(end, compose.onNodeWithTag("message").fetchSemanticsNode().boundsInRoot.top, .5f)
        compose.onNodeWithText("第一段继续流式追加").assertIsDisplayed()
    }

    @Test fun enablingReducedMotionFinishesStaggerImmediately() {
        val reduced = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSyReduceMotion provides reduced.value,
                    LocalSyMotionTempo provides 1.3f) {
                    SyEntrance("staggered", Modifier.testTag("item"), order = 4) { Text("待入场") }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val start = compose.onNodeWithTag("item").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { reduced.value = true }
        compose.mainClock.advanceTimeByFrame()
        val end = compose.onNodeWithTag("item").fetchSemanticsNode().boundsInRoot.top
        assertTrue("系统减少动画应跳过位移与错峰等待", start > end)
        compose.onNodeWithText("待入场").assertIsDisplayed()
    }

    @Test fun disclosureCanReverseAndDoesNotDuplicateActions() {
        val expanded = mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    SyButton("展开", { expanded.value = !expanded.value })
                    SyAnimatedReveal(expanded.value) { SyButton("执行一次", { clicks++ }) }
                }
            }
        }
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithText("执行一次").performClick()
        assertEquals(1, clicks)
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithText("执行一次").assertDoesNotExist()
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithText("执行一次").assertIsDisplayed()
        assertEquals(1, clicks)
    }
}
