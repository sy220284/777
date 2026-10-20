package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalStreamingPreviewStore
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkBackgroundReplyAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun freshSnapshotRetargetsUnfinishedInitialPositionWithoutAnotherReply() {
        var tail by mutableIntStateOf(5)
        var ready by mutableStateOf(false)
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseOldPosition = kotlinx.coroutines.CompletableDeferred<Unit>()
        val positioned = mutableListOf<Int>()
        compose.setContent {
            DshTheme {
                LocalTranscriptInitialPositioner("work", tail, "row:$tail", !ready,
                    onPosition = { index ->
                        if (index == 5) { firstStarted.complete(Unit); releaseOldPosition.await() }
                        positioned += index
                    }, onReady = { ready = true })
                Text(if (ready) "已就绪" else "正在定位")
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(firstStarted.isCompleted); assertFalse(ready); tail = 99 }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(ready); assertEquals(listOf(99), positioned); releaseOldPosition.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(99), positioned) }
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test fun returningAfterBatchedOutputReachesLatestWithoutSendingContinue() {
        lateinit var owner: Owner
        lateinit var list: LazyListState
        lateinit var paused: MutableState<Boolean>
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        val output = MutableStateFlow((0..5).toList())
        compose.runOnIdle { owner = Owner().also { it.lifecycle.currentState = Lifecycle.State.RESUMED } }
        compose.setContent {
            DshTheme {
                val rows by output.collectAsStateWithLifecycle(lifecycleOwner = owner)
                list = rememberLazyListState(initialFirstVisibleItemIndex = 5)
                val catching = remember { mutableStateOf(false) }
                paused = remember { mutableStateOf(false) }
                scope = rememberCoroutineScope()
                LocalWorkTimelineAutoScroller(list, "work", rows.lastIndex, "row:${rows.last()}", true, catching, paused)
                LazyColumn(Modifier.height(240.dp).fillMaxWidth().testTag("timeline"), state = list) {
                    items(rows, key = { it }) { Text("步骤 $it", Modifier.height(64.dp)) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            // The bounded hot window evicts every row that the old viewport knew.
            output.value = (100..179).toList()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(6, list.layoutInfo.totalItemsCount) }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithText("步骤 179").assertIsDisplayed()
        // Reading older content remains an explicit pause even as more output arrives.
        compose.onNodeWithTag("timeline").performTouchInput { swipeDown() }
        compose.waitForIdle()
        var readingIndex = 0
        compose.runOnIdle { readingIndex = list.firstVisibleItemIndex; output.value = (100..189).toList() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(readingIndex, list.firstVisibleItemIndex) }
        compose.runOnIdle { paused.value = true; scope.launch { list.scrollToItem(10) } }
        compose.waitForIdle()
        compose.runOnIdle { output.value = (100..199).toList() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(10, list.firstVisibleItemIndex) }
    }

    @Test fun backgroundRetryAndOutputAppearOnReturnWithNoNewDelta() {
        val previews = LocalStreamingPreviewStore()
        previews.selectSession("work")
        val request = previews.newOwner("work", "request", LocalUsageMode.WORK)
        previews.begin(request)
        compose.setContent { DshTheme { LocalStreamingWorkPreview("work", previews.state) } }
        compose.onNodeWithText("正在等待模型回复…").assertIsDisplayed()
        compose.runOnIdle { previews.selectSession("other"); previews.retry(request, 2) }
        compose.runOnIdle { previews.selectSession("work") }
        compose.onNodeWithText("正在等待重试（第 2 次请求）…").assertIsDisplayed()
        compose.runOnIdle {
            previews.selectSession("other")
            previews.publishAssistant(request, "后台已完成核查")
            previews.selectSession("work")
        }
        compose.onNodeWithText("后台已完成核查").assertIsDisplayed()
    }
}
