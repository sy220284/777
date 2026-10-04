package com.labteto.dshmobile.data

/** One epoch for context lifetime plus one revision per independently refreshed surface. */
internal class SessionAsyncRequestRegistry {
    private var epoch = 0L
    private val revisions = mutableMapOf<String, Long>()

    @Synchronized fun reset() {
        epoch += 1
        revisions.clear()
    }

    @Synchronized fun invalidate(channel: String) {
        revisions[channel] = (revisions[channel] ?: 0L) + 1
    }

    @Synchronized fun capture(channel: String, host: String?, session: String?): SessionAsyncScope {
        invalidate(channel)
        val capturedEpoch = epoch
        val revision = revisions.getValue(channel)
        return SessionAsyncScope(host, session) {
            synchronized(this) { epoch == capturedEpoch && revisions[channel] == revision }
        }
    }
}
