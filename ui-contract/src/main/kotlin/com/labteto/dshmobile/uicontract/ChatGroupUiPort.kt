package com.labteto.dshmobile.uicontract

/**
 * The Chat Owner holds group membership. This contract contains only stable,
 * display-safe gallery identity and an explicit owner-bound command.
 */
data class UiChatGroupChoice(val galleryId: String, val displayName: String)
data class UiChatGroupManagementSnapshot(
    val identity: UiSessionIdentity?,
    val active: Boolean,
    val memberIds: Set<String>,
    val available: List<UiChatGroupChoice>,
)

sealed interface ChatGroupUiCommand {
    val identity: UiSessionIdentity

    data class ConfigureMembers(
        override val identity: UiSessionIdentity,
        val galleryIds: List<String>,
    ) : ChatGroupUiCommand
}

interface ChatGroupUiPort {
    val snapshots: kotlinx.coroutines.flow.Flow<UiChatGroupManagementSnapshot>
        get() = kotlinx.coroutines.flow.flowOf(snapshot())
    fun snapshot(): UiChatGroupManagementSnapshot
    fun submit(command: ChatGroupUiCommand): UiCommandReceipt
}
