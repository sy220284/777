package com.labteto.dshmobile.device.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

data class AccessibilityNodeSnapshot(
    val index: Int,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: String,
    val depth: Int,
    /** Android 13+ can preserve the true node identity across Compose recompositions. */
    val uniqueId: String? = null,
)

class HarnessAccessibilityService : AccessibilityService() {
    private val nodeSelection = AccessibilityNodeSelectionLease()

    override fun onServiceConnected() {
        nodeSelection.invalidate()
        current = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Android/Compose can emit unrelated content events between a find and an action.
        // Keep the inspected identity; nodeAt checks it against the LIVE active window
        // at execution time. Real window/control changes still reject stale selections.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        nodeSelection.invalidate()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    fun snapshot(maxNodes: Int = 400): List<AccessibilityNodeSnapshot> {
        val root = rootInActiveWindow ?: run {
            nodeSelection.invalidate()
            return emptyList()
        }
        val output = mutableListOf<AccessibilityNodeSnapshot>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && output.size < maxNodes.coerceIn(1, 2_000)) {
            val (node, depth) = queue.removeFirst()
            output += describeNode(output.size, node, depth)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it to depth + 1) }
            }
        }
        nodeSelection.replace(root.windowId, output)
        return output
    }

    private fun describeNode(index: Int, node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeSnapshot {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        return AccessibilityNodeSnapshot(
            index = index,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            clickable = node.isClickable,
            editable = node.isEditable,
            bounds = bounds.toShortString(),
            depth = depth,
            uniqueId = if (android.os.Build.VERSION.SDK_INT >= 33) node.uniqueId else null,
        )
    }

    fun clickNode(index: Int): Boolean {
        val node = nodeAt(index) ?: return false
        val target = if (node.isClickable) node else clickableAncestor(node) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Compose controls may only accept ACTION_SET_TEXT once focused.
     * Retry the same idempotent replacement on the same refreshed node, never another field.
     */
    private fun setEditableText(target: AccessibilityNodeInfo, value: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return true
        if (!target.isVisibleToUser || !target.isFocusable) return false
        if (!target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return false
        return target.refresh() && target.isEditable &&
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targets = root.findAccessibilityNodeInfosByText(text).mapNotNull { candidate ->
            if (!candidate.isVisibleToUser) null
            else if (candidate.isClickable) candidate else clickableAncestor(candidate)
        }.distinct()
        val target = requireUniqueAccessibilityTarget(targets, "点击") ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * One Android input operation: choose the intended field, replace its text, then
     * check the resulting accessibility state. Do not report ACTION_SET_TEXT acceptance
     * as completed input; Compose can accept a request without changing the field.
     */
    suspend fun inputText(
        nodeIndex: Int?,
        viewId: String?,
        searchText: String?,
        value: String,
    ): Boolean {
        require(listOf(nodeIndex != null, !viewId.isNullOrBlank(), !searchText.isNullOrBlank())
            .count { it } <= 1) {
            "输入目标只能选择 node、id、text 中的一种；全部留空时使用当前已聚焦输入框"
        }
        val root = rootInActiveWindow ?: return false
        val target = when {
            nodeIndex != null -> nodeAt(nodeIndex, rebindEditable = true)
            !viewId.isNullOrBlank() -> requireUniqueAccessibilityTarget(
                root.findAccessibilityNodeInfosByViewId(viewId)
                    .filter { it.isVisibleToUser && it.isEditable }, "输入文字")
            !searchText.isNullOrBlank() -> requireUniqueAccessibilityTarget(
                root.findAccessibilityNodeInfosByText(searchText)
                    .filter { it.isVisibleToUser && it.isEditable }, "输入文字")
            else -> root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isVisibleToUser && it.isEditable }
                ?: onlyVisibleEditable(root)
        }?.takeIf { it.isVisibleToUser && it.isEditable } ?: return false

        val windowId = target.windowId
        val originalBounds = android.graphics.Rect().also(target::getBoundsInScreen)
        val targetId = target.viewIdResourceName
        if (!setEditableText(target, value)) return false

        // Read the same control after the framework has applied its semantics update.
        // Retry verification only, not the mutating text action.
        repeat(4) {
            kotlinx.coroutines.delay(100L)
            if (target.refresh() && target.isEditable &&
                target.text?.toString().orEmpty() == value) return true
            val currentRoot = rootInActiveWindow ?: return false
            if (currentRoot.windowId != windowId) return false
            val currentTarget = if (targetId != null) {
                val matches = currentRoot.findAccessibilityNodeInfosByViewId(targetId)
                    .filter { it.isVisibleToUser && it.isEditable }
                matches.singleOrNull()
            } else currentRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { focused ->
                    focused.isEditable && focused.isVisibleToUser &&
                        android.graphics.Rect().also(focused::getBoundsInScreen) == originalBounds
                }
            if (currentTarget?.text?.toString().orEmpty() == value && currentTarget != null)
                return true
        }
        return false
    }

    /** No selector is needed for a page with exactly one visible editable field. */
    private fun onlyVisibleEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var sole: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.isEditable) {
                if (sole != null) return null
                sole = node
            }
            for (childIndex in 0 until node.childCount) {
                node.getChild(childIndex)?.let(queue::add)
            }
        }
        return sole
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun scroll(direction: String): Boolean {
        val action = when (direction.lowercase()) {
            "forward", "down", "next" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "backward", "up", "previous" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> error("滚动方向仅支持 forward/down 或 backward/up")
        }
        val root = rootInActiveWindow ?: return false
        if (root.performAction(action)) return true
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable && node.performAction(action)) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return false
    }

    fun scrollForward(): Boolean = scroll("forward")

    suspend fun tap(
        x: Float,
        y: Float,
        durationMillis: Long = 60L,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis.coerceAtLeast(1L)))
            .build()
        return awaitGestureCompletion(gesture, durationMillis)
    }

    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMillis: Long = 350L,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis.coerceAtLeast(1L)))
            .build()
        return awaitGestureCompletion(gesture, durationMillis)
    }

    /**
     * dispatchGesture() returning true means Android only accepted the request. Await the real
     * completion callback before starting a dependent gesture or reporting execution success.
     * Cancellation/timeout is surfaced to the caller so unknown effects are never auto-replayed.
     */
    private suspend fun awaitGestureCompletion(
        gesture: GestureDescription,
        durationMillis: Long,
    ): Boolean = withTimeout(durationMillis.coerceIn(1L, 60_000L) + 10_000L) {
        suspendCancellableCoroutine { continuation ->
            val scheduled = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!scheduled && continuation.isActive) continuation.resume(false)
        }
    }

    suspend fun screenshotPng(): ByteArray = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?: run {
                                continuation.resumeWithException(IllegalStateException("无法转换截图缓冲区"))
                                return
                            }
                        val output = ByteArrayOutputStream()
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            continuation.resumeWithException(IllegalStateException("截图编码失败"))
                            return
                        }
                        continuation.resume(output.toByteArray())
                    } finally {
                        hardwareBuffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    continuation.resumeWithException(
                        IllegalStateException("无障碍截图失败，错误码：$errorCode"),
                    )
                }
            },
        )
    }

    private fun nodeAt(index: Int, rebindEditable: Boolean = false): AccessibilityNodeInfo? {
        if (index < 0) return null
        val root = rootInActiveWindow ?: run {
            nodeSelection.invalidate()
            return null
        }
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var nextIndex = 0
        val current = if (rebindEditable) mutableListOf<AccessibilityNodeSnapshot>() else null
        // Only editable nodes can be a text target. Do not retain every Android node
        // merely to re-bind one input, especially on large Compose screens.
        val editableNodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            val currentIndex = nextIndex++
            if (!rebindEditable && currentIndex == index) {
                // Clicks only need the selected node, not a scan of the entire UI.
                nodeSelection.requireCurrent(
                    index, root.windowId, describeNode(currentIndex, node, depth),
                )
                return node
            }
            if (rebindEditable) {
                current!!.add(describeNode(currentIndex, node, depth))
                if (node.isEditable) editableNodes[currentIndex] = node
            }
            for (childIndex in 0 until node.childCount) {
                node.getChild(childIndex)?.let { queue.add(it to depth + 1) }
            }
        }
        if (!rebindEditable) {
            nodeSelection.invalidate()
            return null
        }
        val resolved = nodeSelection.resolveEditableIndex(index, root.windowId, requireNotNull(current))
        return editableNodes[resolved]
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var currentNode = node.parent
        repeat(8) {
            val value = currentNode ?: return null
            if (value.isClickable) return value
            currentNode = value.parent
        }
        return null
    }

    companion object {
        @Volatile
        private var current: WeakReference<HarnessAccessibilityService>? = null

        fun active(): HarnessAccessibilityService? = current?.get()
    }
}
