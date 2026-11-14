package com.labteto.dshmobile.device.accessibility

/**
 * Node numbers identify a selection from the last inspected window, not a permanent
 * index into a potentially changing Compose semantics tree. Every action re-reads
 * the live tree before it is allowed to operate.
 */
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
        check(index >= 0 && snapshot?.windowId == windowId && observed === snapshot &&
            snapshot.nodes.getOrNull(index) == candidate
        ) { EXPIRED_MESSAGE }
    }

    /**
     * Editable Compose nodes commonly have no view id. Re-bind a selected input to
     * its current live identity, using a unique view id when available, otherwise
     * its unique on-screen geometry and semantics class in the same window.
     * Ambiguous or replaced controls are never chosen by list position.
     */
    fun resolveEditableIndex(
        index: Int,
        windowId: Int,
        current: List<AccessibilityNodeSnapshot>,
    ): Int {
        val snapshot = observed ?: throw IllegalStateException(EXPIRED_MESSAGE)
        check(index >= 0 && snapshot.windowId == windowId) { EXPIRED_MESSAGE }
        val original = snapshot.nodes.getOrNull(index)
        check(original != null && original.editable) { EXPIRED_MESSAGE }
        if (current.getOrNull(index) == original) return index

        val editable = current.filter {
            it.editable && it.className == original.className &&
                it.viewId == original.viewId
        }
        val candidates = if (!original.viewId.isNullOrBlank()) {
            // The same resource id can occur in repeated form rows.
            val matchingBounds = editable.filter { it.bounds == original.bounds }
            if (matchingBounds.size == 1) matchingBounds
            else if (editable.size == 1) editable else emptyList()
        } else {
            // Compose fields typically omit view ids. Never guess among siblings.
            editable.filter {
                it.bounds == original.bounds &&
                    it.contentDescription == original.contentDescription
            }
        }
        check(observed === snapshot) { EXPIRED_MESSAGE }
        return candidates.singleOrNull()?.index
            ?: throw IllegalStateException(EXPIRED_MESSAGE)
    }

    private companion object {
        const val EXPIRED_MESSAGE =
            "目标控件已经更换或无法唯一定位；请重新调用 android_find 后再操作"
    }
}

/**
 * Text-based accessibility actions are intentionally strict: the first text match may refer
 * to a different destructive button or input on a dynamically updated Android page.
 */
internal fun <T> requireUniqueAccessibilityTarget(candidates: List<T>, action: String): T? {
    check(candidates.size <= 1) {
        "存在多个匹配的控件，无法安全地$action；请使用 android_find 精确定位节点编号"
    }
    return candidates.singleOrNull()
}
