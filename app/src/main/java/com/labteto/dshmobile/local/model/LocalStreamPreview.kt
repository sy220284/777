package com.labteto.dshmobile.local.model



/** Keeps the visible tail of a model reply and publishes it at a screen-friendly rate. */
internal class LocalStreamPreview(
    private val maxChars: Int,
    private val minIntervalMs: Long,
    private val clockMs: () -> Long,
    private val publish: (String) -> Unit,
) {
    // Fixed-capacity UTF-16 ring: trimming a full stream no longer shifts up to 64KiB
    // of existing text for every tiny incoming chunk.
    private val chars = CharArray(maxChars)
    private var head = 0
    private var length = 0

    private fun charAt(index: Int): Char = chars[(head + index) % maxChars]

    private fun discard(count: Int) {
        head = (head + count) % maxChars
        length -= count
    }

    private fun write(value: String) {
        for (ch in value) {
            chars[(head + length) % maxChars] = ch
            length++
        }
    }

    private fun snapshot(): String = buildString(length) {
        for (index in 0 until length) append(charAt(index))
    }

    private var lastPublishMs: Long? = null
    private var dirty = false

    init {
        require(maxChars > 0)
        require(minIntervalMs >= 0)
    }

    fun append(delta: String) {
        if (delta.isEmpty()) return
        if (delta.length >= maxChars) {
            head = 0
            length = 0
            write(takeLastWithoutSplittingSurrogatePair(delta, maxChars))
        } else {
            var overflow = length + delta.length - maxChars
            if (
                overflow > 0 && overflow < length &&
                Character.isHighSurrogate(charAt(overflow - 1)) &&
                Character.isLowSurrogate(charAt(overflow))
            ) {
                overflow += 1
            }
            if (overflow > 0) discard(overflow.coerceAtMost(length))
            write(delta)
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
        publish(snapshot())
        lastPublishMs = now
        dirty = false
    }
}
