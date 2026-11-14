package com.labteto.dshmobile.device.accessibility

/** A node number is valid only for the last observed, unchanged Android screen. */
internal class AccessibilityNodeSelectionLease {
    private data class Observed(
        val windowId: Int,
        val nodes: List<AccessibilityNodeSnapshot>,
    )

    @Volatile private var observed: Observed? = null

    fun replace(windowId: Int, nodes: List<AccessibilityNodeSnapshot>) {
        observed = Observed(windowId, nodes.toList())
    }

    fun invalidate() {
        observed = null
    }

    fun requireCurrent(index: Int, windowId: Int, candidate: AccessibilityNodeSnapshot) {
        val snapshot = observed
        check(index >= 0 && snapshot?.windowId == windowId &&
            snapshot.nodes.getOrNull(index) == candidate && observed === snapshot
        ) { "控件编号已过期或当前界面已改变；请重新调用 android_screen 或 android_find 后再操作" }
    }
}
