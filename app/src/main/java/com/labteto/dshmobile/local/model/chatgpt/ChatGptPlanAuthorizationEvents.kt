package com.labteto.dshmobile.local.model.chatgpt

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class ChatGptPlanAuthorizationEvents @Inject constructor() {
    private val _invalidatedAccounts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val invalidatedAccounts = _invalidatedAccounts.asSharedFlow()

    fun invalidate(accountId: String) {
        if (accountId.isNotBlank()) _invalidatedAccounts.tryEmit(accountId)
    }
}
