package com.labteto.dshmobile.local

/** Keeps the visible tail of a model reply and publishes it at a screen-friendly rate. */
internal class LocalStreamPreview(
    private val maxChars: Int,
    private val minIntervalMs: Long,
    private val clockMs: () -> Long,
    private val publish: (String) -> Unit,
) {
    private val text = StringBuilder()
    private var lastPublishMs: Long? = null
    private var dirty = false

    init {
        require(maxChars > 0)
        require(minIntervalMs >= 0)
    }

    fun append(delta: String) {
        if (delta.isEmpty()) return
        if (delta.length >= maxChars) {
            text.clear()
            text.append(delta.takeLast(maxChars))
        } else {
            val overflow = text.length + delta.length - maxChars
            if (overflow > 0) text.delete(0, overflow)
            text.append(delta)
        }
        dirty = true
        val now = clockMs()
        if (lastPublishMs == null || now - lastPublishMs!! >= minIntervalMs) publishAt(now)
    }

    /** Publish a final short burst before the durable answer replaces the preview. */
    fun flush() {
        if (dirty) publishAt(clockMs())
    }

    private fun publishAt(now: Long) {
        publish(text.toString())
        lastPublishMs = now
        dirty = false
    }
}
