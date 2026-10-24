package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage

/**
 * Foreground UI window, independent from the wider model/runtime hot transcript.
 * Keeping the anchor when a new message arrives avoids dropping a row the
 * reader is looking at; the window resets only on a new session/rewrite/eviction.
 */
internal fun localTranscriptVisibleLiveWindow(
    messages: List<LocalHarnessMessage>,
    anchorMessageId: String?,
    initialQuota: Int,
): List<LocalHarnessMessage> {
    if (messages.isEmpty()) return emptyList()
    val anchorIndex = anchorMessageId?.let { anchor ->
        messages.indexOfFirst { it.id == anchor }
    } ?: -1
    return if (anchorIndex >= 0) {
        messages.drop(anchorIndex)
    } else {
        messages.takeLast(initialQuota.coerceAtLeast(1))
    }
}
