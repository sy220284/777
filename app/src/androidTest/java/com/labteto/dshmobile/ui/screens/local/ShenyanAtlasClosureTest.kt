package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.ui.audit.captureUiAudit
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiQueuedUserInput
import com.labteto.dshmobile.uicontract.UiQueuedUserPort
import com.labteto.dshmobile.uicontract.UiQueuedUserSnapshot
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.shenyan.designsystem.components.SyActionEntry
import com.shenyan.designsystem.components.SyPullRefresh
import com.shenyan.designsystem.components.SyRadialActionMenu
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShenyanAtlasClosureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun queueDragCommitsOnlyOnDropThroughTheExistingOwner() {
        val identity = UiSessionIdentity("queue-fixture", "activation", 1, null, null)
        val writes = AtomicInteger()
        var snapshot = UiQueuedUserSnapshot(identity,
            listOf(UiQueuedUserInput("one", "第一条"), UiQueuedUserInput("two", "第二条")))
        val port = object : UiQueuedUserPort {
            override fun snapshot() = snapshot
            override fun edit(identity: UiSessionIdentity, messageId: String, replacement: String) = UiCommandReceipt.NotAdmitted
            override fun remove(identity: UiSessionIdentity, messageId: String) = UiCommandReceipt.NotAdmitted
            override fun reorder(identity: UiSessionIdentity, orderedIds: List<String>): UiCommandReceipt {
                check(identity == snapshot.identity)
                val byId = snapshot.inputs.associateBy { it.id }
                snapshot = snapshot.copy(inputs = orderedIds.map(byId::getValue))
                writes.incrementAndGet()
                return UiCommandReceipt.Accepted
            }
        }
        compose.setContent { DshTheme { LocalQueuedUserQueueSheet(port, identity.sessionId, 2) {} } }
        compose.onNodeWithTag("sy-snap-stop-2").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("queue-drag-two")).fetchSemanticsNodes().isNotEmpty()
        }
        val first = compose.onNodeWithTag("queue-drag-one")
        val second = compose.onNodeWithTag("queue-drag-two")
        val distance = second.getUnclippedBoundsInRoot().top.value - first.getUnclippedBoundsInRoot().top.value
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        first.performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, distance * density / 4)) }
        }
        first.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "将移到第 2 项"))
        assertEquals(0, writes.get())
        captureUiAudit(compose, "queue-drag-before-drop")
        first.performTouchInput { up() }
        compose.waitUntil(10_000) { writes.get() == 1 }
        assertEquals(listOf("two", "one"), snapshot.inputs.map { it.id })
    }

    @Test fun radialMenuInvokesOneActionAndDismisses() {
        val open = mutableStateOf(true)
        var chosen = ""
        compose.setContent {
            DshTheme {
                if (open.value) SyRadialActionMenu(
                    "文件操作", listOf(SyActionEntry("open", "打开"), SyActionEntry("share", "分享"),
                        SyActionEntry("save", "保存")),
                    onDismiss = { open.value = false }, onChoose = { chosen = it },
                )
            }
        }
        captureUiAudit(compose, "file-radial-menu")
        compose.onNodeWithTag("menu-item:保存").performClick()
        compose.runOnIdle {
            assertEquals("save", chosen)
            assertTrue(!open.value)
        }
    }

    @Test fun pullFromListTopCallsRefreshOnce() {
        val refreshing = mutableStateOf(false)
        var requests = 0
        compose.setContent {
            DshTheme {
                SyPullRefresh(refreshing.value, onRefresh = { requests++; refreshing.value = true }) {
                    LazyColumn(Modifier.fillMaxSize().testTag("refresh-test-list")) {
                        items(30) { Text("工作任务 $it") }
                    }
                }
            }
        }
        compose.onNodeWithTag("refresh-test-list").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, requests); assertTrue(refreshing.value); refreshing.value = false }
    }

    @Test fun realVideoSeeksWithButtonAndDoubleTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "shenyan-video-fixture.mp4")
        instrumentation.context.assets.open(file.name).use { source ->
            file.outputStream().use { destination -> source.copyTo(destination) }
        }
        val error = mutableStateOf<String?>(null)
        compose.setContent { DshTheme { InlineVideoPlayer(null, file, { error.value = it }, {}) } }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription("暂停")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("暂停").performClick()
        fun elapsedSeconds(): Int {
            val text = compose.onNodeWithTag("sy-media-elapsed").fetchSemanticsNode()
                .config[SemanticsProperties.Text].first().text
            return text.substringBefore(':').toInt() * 60 + text.substringAfter(':').toInt()
        }
        val start = elapsedSeconds()
        compose.onNodeWithContentDescription("前进 10 秒").performClick()
        val forwarded = elapsedSeconds()
        assertTrue(forwarded >= start + 9)
        compose.onNodeWithTag("sy-video-gesture-region").performTouchInput {
            doubleClick(Offset(width * .20f, height * .5f))
        }
        assertTrue(elapsedSeconds() <= forwarded - 9)
        compose.runOnIdle { assertEquals(null, error.value) }
        captureUiAudit(compose, "video-shenyan-transport")
    }
}
