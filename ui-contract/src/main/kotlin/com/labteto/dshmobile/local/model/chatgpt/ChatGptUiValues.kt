package com.labteto.dshmobile.local.model.chatgpt

/** Public login summaries contain no tokens or authorization codes. */
enum class ChatGptAuthPhase {
    DISCONNECTED,
    PREPARING,
    WAITING_FOR_BROWSER,
    EXCHANGING_CODE,
    VALIDATING_IDENTITY,
    LOADING_MODELS,
    CONNECTED,
    UNVERIFIED,
    ERROR,
}

data class ChatGptAccountSummary(
    val id: String,
    val clientId: String,
    val email: String?,
    val displayName: String?,
    val signedIn: Boolean,
    val sharingEnabled: Boolean,
)

data class ChatGptModelOption(
    val slug: String,
    val displayName: String,
)

data class ChatGptUiState(
    val phase: ChatGptAuthPhase = ChatGptAuthPhase.DISCONNECTED,
    val accounts: List<ChatGptAccountSummary> = emptyList(),
    val selectedAccountId: String? = null,
    val models: List<ChatGptModelOption> = emptyList(),
    val error: String? = null,
    val pendingAccountId: String? = null,
) {
    val selectedAccount: ChatGptAccountSummary?
        get() = accounts.firstOrNull { it.id == selectedAccountId }

    val signedIn: Boolean
        get() = selectedAccount?.signedIn == true && phase != ChatGptAuthPhase.DISCONNECTED

    val connected: Boolean
        get() = selectedAccount?.let { account ->
            account.signedIn &&
                account.sharingEnabled &&
                phase == ChatGptAuthPhase.CONNECTED
        } == true
}

