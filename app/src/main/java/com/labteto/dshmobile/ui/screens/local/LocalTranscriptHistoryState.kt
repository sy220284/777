package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalHarnessMessage

internal data class LocalTranscriptHistoryState(
    val sessionId: String = "",
    val olderMessages: List<LocalHarnessMessage> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)
