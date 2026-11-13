package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalTranscriptPagingAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dragAtUnscrollableTopLoadsAndLoadingStateDoesNotCancelRequest() {
        val enabled = mutableStateOf(true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = AtomicBoolean(false)
        val calls = AtomicInteger(0)

        compose.setContent {
            DshTheme {
                val listState = rememberLazyListState()
                LocalTranscriptAutoPager(
                    sessionId = "history",
                    listState = listState,
                    enabled = enabled.value,
                    onLoadOlder = {
                        calls.incrementAndGet()
                        enabled.value = false
                        started.complete(Unit)
                        release.await()
                        completed.set(true)
                        Result.success(24)
                    },
                )
                // The newest page fits the viewport, so list coordinates cannot
                // move in response to a downward drag at the history boundary.
                LazyColumn(
                    modifier = Modifier.height(240.dp).fillMaxWidth().testTag("timeline"),
                    state = listState,
                ) {
                    items(listOf("最近消息 1", "最近消息 2")) { message ->
                        Text(message, Modifier.height(48.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("timeline").performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { started.isCompleted }
        compose.runOnIdle { assertEquals(1, calls.get()); assertTrue(!enabled.value) }
        // enabled=false is exactly what the real loading indicator causes.
        // It must not cancel the active request or its owning collector.
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil(10_000) { completed.get() }
        compose.runOnIdle { assertEquals(1, calls.get()) }
    }
}
