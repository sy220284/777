package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.Job
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease

/** Chat-owned lazy turn entry; an admission lease is handed to the dispatcher without a second owner. */
internal interface LocalChatTurnPort {
    fun start(
        content: String,
        memoryInput: String,
        sourceMessageId: String,
        sessionLease: LocalSessionRuntimeLease? = null,
    ): Job
}
