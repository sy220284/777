package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatRuntime
import com.labteto.dshmobile.local.presentation.LocalChatUiFacade
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.uicontract.ChatGroupUiCommand
import com.labteto.dshmobile.uicontract.ChatGroupUiPort
import com.labteto.dshmobile.uicontract.UiChatGroupChoice
import com.labteto.dshmobile.uicontract.UiChatGroupManagementSnapshot
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner adapter for group membership, never a second group database.
 * The App composition checks captured foreground identity before handing
 * a command to the existing Chat membership coordinator.
 */
@Singleton
class LocalChatGroupUiAdapter @Inject internal constructor(
    private val store: LocalRuntimeStateStore,
    private val chat: LocalChatRuntime,
    private val gallery: LocalChatUiFacade,
) : ChatGroupUiPort {
    override fun snapshot(): UiChatGroupManagementSnapshot {
        val state = synchronized(store.foregroundRunHandle.lock) {
            val current = store.state.value
            val active = current.usageMode == LocalUsageMode.CHAT &&
                !current.loading && !store.sessionTransitioning
            Triple(
                store.uiBindingIdentity(current).takeIf { active },
                current.chat.groupChat.takeIf { active && it.enabled },
                active,
            )
        }
        val options = if (state.first != null && state.second != null) {
            gallery.galleryEntries().map { UiChatGroupChoice(it.id, it.persona.name) }
        } else emptyList()
        return UiChatGroupManagementSnapshot(
            identity = state.first,
            active = state.second != null,
            memberIds = state.second?.members?.map { it.galleryId }?.toSet().orEmpty(),
            available = options,
        )
    }

    override fun submit(command: ChatGroupUiCommand): UiCommandReceipt =
        synchronized(store.foregroundRunHandle.lock) {
            val current = store.state.value
            if (store.sessionTransitioning) return@synchronized UiCommandReceipt.Rejected(
                UiCommandRejection.SESSION_TRANSITION)
            if (current.usageMode != LocalUsageMode.CHAT || current.loading ||
                store.uiBindingIdentity(current) != command.identity
            ) return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            if (!current.chat.groupChat.enabled) return@synchronized UiCommandReceipt.Rejected(
                UiCommandRejection.UNSUPPORTED)
            when (command) {
                is ChatGroupUiCommand.ConfigureMembers -> {
                    val selected = command.galleryIds.distinct()
                    if (selected.size !in 2..6) return@synchronized UiCommandReceipt.Rejected(
                        UiCommandRejection.UNSUPPORTED)
                    val available = gallery.galleryEntries().associateBy { it.id }
                    if (selected.any { it !in available }) return@synchronized UiCommandReceipt.Rejected(
                        UiCommandRejection.UNSUPPORTED)
                    val entries = selected.map { available.getValue(it) }
                    if (chat.configureGroupChatMembers(entries)) UiCommandReceipt.Accepted
                    else UiCommandReceipt.NotAdmitted
                }
            }
        }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalChatGroupUiBindings {
    @dagger.Binds abstract fun bindGroupPort(adapter: LocalChatGroupUiAdapter): ChatGroupUiPort
}
