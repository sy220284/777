package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocalConversationScrollBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun streamingTailGrowsWithoutStealingPositionFromHistoryReader() {
        lateinit var listState: LazyListState
        val tailHeight = mutableStateOf(64.dp)
        val trackingEnabled = mutableStateOf(true)
        compose.setContent {
            DshTheme {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items((0 until 5).toList()) { index ->
                            Text(
                                text = "previous-step-$index",
                                modifier = Modifier.fillMaxWidth().height(80.dp),
                            )
                        }
                        item(key = "streaming-tail") {
                            Text(
                                text = "streaming-tail",
                                modifier = Modifier.fillMaxWidth().height(tailHeight.value),
                            )
                        }
                    }
                    LocalWorkStreamingTailFollower(
                        listState = listState,
                        enabled = trackingEnabled.value,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { listState.scrollToItem(5) } }
        compose.waitForIdle()
        compose.runOnIdle { tailHeight.value = 112.dp }
        compose.waitForIdle()
        compose.runOnIdle {
            val info = listState.layoutInfo
            val tail = info.visibleItemsInfo.last()
            assertEquals(info.totalItemsCount - 1, tail.index)
            assertTrue(tail.offset + tail.size - info.viewportEndOffset <= 4)
        }
        // A large Markdown chunk must not strand the viewport above the live tail.
        compose.runOnIdle { tailHeight.value = 560.dp }
        compose.waitForIdle()
        compose.runOnIdle {
            val info = listState.layoutInfo
            val tail = info.visibleItemsInfo.last()
            assertEquals(info.totalItemsCount - 1, tail.index)
            assertTrue(tail.offset + tail.size - info.viewportEndOffset <= 4)
        }
        compose.runOnIdle { runBlocking { listState.scrollToItem(0) } }
        compose.waitForIdle()
        compose.runOnIdle { tailHeight.value = 180.dp }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
        }
    }

    @Test
    fun visibleTailTracksComposerAndImeViewportShrink() {
        lateinit var listState: LazyListState
        val viewportHeight = mutableStateOf(420.dp)
        val tailViewportAnchor = mutableStateOf<Int?>(null)

        compose.setContent {
            DshTheme {
                listState = rememberLazyListState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(viewportHeight.value),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items((0 until 30).toList(), key = { it }) { index ->
                            Text(
                                text = "tail-row-$index",
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                            )
                        }
                    }
                    LocalComposerTailFollower(
                        listState = listState,
                        anchoredViewportExtent = tailViewportAnchor.value,
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            runBlocking { listState.scrollToItem(23, 20) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("tail-row-29").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(listState.isConversationTailVisible())
            tailViewportAnchor.value = listState.conversationViewportExtent()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            viewportHeight.value = 220.dp
        }
        compose.waitForIdle()

        compose.onNodeWithText("tail-row-29").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(listState.isConversationTailVisible())
            assertTrue(listState.firstVisibleItemIndex > 23)
        }
    }

    @Test
    fun focusingComposerWhileReadingHistoryDoesNotStealPosition() {
        lateinit var listState: LazyListState
        val viewportHeight = mutableStateOf(420.dp)
        val tailViewportAnchor = mutableStateOf<Int?>(null)

        compose.setContent {
            DshTheme {
                listState = rememberLazyListState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(viewportHeight.value),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items((0 until 30).toList(), key = { it }) { index ->
                            Text(
                                text = "history-row-$index",
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                            )
                        }
                    }
                    LocalComposerTailFollower(
                        listState = listState,
                        anchoredViewportExtent = tailViewportAnchor.value,
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            runBlocking { listState.scrollToItem(10) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(listState.isConversationTailVisible())
            tailViewportAnchor.value = null
        }

        compose.runOnIdle {
            viewportHeight.value = 220.dp
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(10, listState.firstVisibleItemIndex)
        }
    }
    @Test
    fun newWorkStepsFollowAtBottomPauseInHistoryAndResumeAfterReturning() {
        lateinit var listState: LazyListState
        val rows = mutableIntStateOf(12)
        val catchingUp = mutableStateOf(false)
        compose.setContent {
            DshTheme {
                listState = rememberLazyListState()
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(rows.intValue, key = { "work-$it" }) { index ->
                            Text("work-$index", Modifier.fillMaxWidth().height(64.dp))
                        }
                    }
                    LocalWorkTimelineAutoScroller(
                        listState = listState, sessionId = "work-test",
                        tailIndex = rows.intValue - 1, tailKey = "work-" + (rows.intValue - 1),
                        isReady = true, isCatchingUp = catchingUp,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { listState.scrollToItem(9) } }
        compose.waitForIdle()

        // A single incoming operation glides into the bottom viewport.
        compose.runOnIdle { rows.intValue = 13 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.index == 12 })
            assertFalse(catchingUp.value)
        }

        // Reading prior history stops the movement even when several events land at once.
        compose.runOnIdle { runBlocking { listState.scrollToItem(2) } }
        compose.waitForIdle()
        compose.runOnIdle { rows.intValue = 16 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, listState.firstVisibleItemIndex) }

        // User scrolls back to the newest item. Following resumes on the next update.
        compose.runOnIdle { runBlocking { listState.scrollToItem(13) } }
        compose.waitForIdle()
        compose.runOnIdle { rows.intValue = 17 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.index == 16 })
        }
    }

    @Test
    fun workTimelineAutoFollowDoesNotTreatLargeParagraphTopAsBottom() {
        assertTrue(shouldFollowNewWorkStep(10, 10, 10, false, false))
        assertTrue(shouldFollowNewWorkStep(10, 10, null, false, false))
        assertFalse(shouldFollowNewWorkStep(10, 10, 600, false, false))
        assertFalse(shouldFollowNewWorkStep(10, 7, null, false, false))
        assertFalse(shouldFollowNewWorkStep(10, 10, 0, true, false))
        assertFalse(shouldFollowNewWorkStep(10, 10, 0, false, true))
    }

}
