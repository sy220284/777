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

data class AccessibilityNodeSnapshot(
    val text: String?,
    val viewId: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: String,
    val depth: Int,
)

class HarnessAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        current = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    fun snapshot(maxNodes: Int = 400): List<AccessibilityNodeSnapshot> {
        val root = rootInActiveWindow ?: return emptyList()
        val output = mutableListOf<AccessibilityNodeSnapshot>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && output.size < maxNodes.coerceIn(1, 2_000)) {
            val (node, depth) = queue.removeFirst()
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            output += AccessibilityNodeSnapshot(
                text = node.text?.toString() ?: node.contentDescription?.toString(),
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                clickable = node.isClickable,
                editable = node.isEditable,
                bounds = bounds.toShortString(),
                depth = depth,
            )
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it to depth + 1) }
            }
        }
        return output
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByText(text)
            .firstOrNull { it.isVisibleToUser && (it.isClickable || clickableAncestor(it) != null) }
            ?.let { node -> (if (node.isClickable) node else clickableAncestor(node))?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            == true
    }

    fun setText(searchText: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findAccessibilityNodeInfosByText(searchText)
            .firstOrNull { it.isEditable }
            ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun scrollForward(): Boolean =
        rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true

    fun tap(x: Float, y: Float, durationMillis: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis.coerceAtLeast(1L)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMillis: Long = 350L,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis.coerceAtLeast(1L)))
            .build()
        return dispatchGesture(gesture, null, null)
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
