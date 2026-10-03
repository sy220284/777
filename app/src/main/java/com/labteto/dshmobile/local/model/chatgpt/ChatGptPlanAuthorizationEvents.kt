package com.labteto.dshmobile.local.model.chatgpt

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class ChatGptPlanAuthorizationEvents @Inject constructor() {
    // Auth may refresh before Local Harness is instantiated. Replay keeps a bounded set of recent
    // invalidations so the model runtime can retire stale plan profiles when it starts later.
    private val _invalidatedAccounts = MutableSharedFlow<String>(replay = 16, extraBufferCapacity = 16)
    val invalidatedAccounts = _invalidatedAccounts.asSharedFlow()

    fun invalidate(accountId: String) {
        if (accountId.isNotBlank()) _invalidatedAccounts.tryEmit(accountId)
    }
}
