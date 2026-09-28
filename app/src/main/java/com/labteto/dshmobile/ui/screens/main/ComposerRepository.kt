package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal data class ComposerKey(val host: String, val sessionId: String)

/** Owned by SessionStore, so a gallery callback and an upload never follow UI navigation. */
internal class ComposerDraft(val key: ComposerKey) {
    var text by mutableStateOf("")
    var mode by mutableStateOf("queue")
    var preparing by mutableStateOf(false)
    var submitting by mutableStateOf(false)
    val attachments = mutableStateListOf<PendingAttachment>()

    fun restoreRejected(submittedText: String, submitted: List<PendingAttachment>) {
        if (text.isBlank()) text = submittedText
        attachments.addAll(0, submitted.filterNot { it in attachments })
    }
}

internal class ComposerRepository(
    private val maxCachedDrafts: Int = MAX_CACHED_DRAFTS,
) {
    init {
        require(maxCachedDrafts > 0) { "草稿缓存上限必须大于 0" }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Drafts survive normal session switches, but they must not live for the lifetime of
     * SessionStore without a bound: image drafts retain their full base64 payload and preview.
     */
    private val drafts = LinkedHashMap<ComposerKey, ComposerDraft>(16, 0.75f, true)

    fun get(key: ComposerKey): ComposerDraft {
        drafts[key]?.let { return it }
        return ComposerDraft(key).also { draft ->
            drafts[key] = draft
            trimDrafts()
        }
    }

    var imagePickTarget: Pair<ComposerDraft, com.labteto.dshmobile.core.wire.dto.ImageLimitsView>? = null
    var filePickTarget: ComposerDraft? = null

    private fun trimDrafts() {
        while (drafts.size > maxCachedDrafts) {
            val protectedKeys = setOfNotNull(imagePickTarget?.first?.key, filePickTarget?.key)
            val oldestEvictable = drafts.keys.firstOrNull { it !in protectedKeys } ?: return
            drafts.remove(oldestEvictable)
        }
    }

    private companion object {
        const val MAX_CACHED_DRAFTS = 12
    }
}
