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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
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
    override val snapshots = store.state.map { snapshot() }.distinctUntilChanged()

    override fun snapshot(): UiChatGroupManagementSnapshot = synchronized(store.foregroundRunHandle.lock) {
        val current = store.state.value
        val usable = current.usageMode == LocalUsageMode.CHAT && !current.loading && !store.sessionTransitioning
        val group = current.chat.groupChat
        UiChatGroupManagementSnapshot(
            identity = store.uiBindingIdentity(current).takeIf { usable },
            active = usable && group.enabled,
            memberIds = if (usable && group.enabled) group.members.map { it.galleryId }.toSet() else emptySet(),
            available = if (usable) gallery.galleryEntries().map { UiChatGroupChoice(it.id, it.persona.name) } else emptyList(),
            announcement = if (usable && group.enabled) group.announcement else "",
            busy = !usable || current.kernel.running,
            minimumMembers = com.labteto.dshmobile.local.chat.MIN_GROUP_CHAT_MEMBERS,
            maximumMembers = com.labteto.dshmobile.local.chat.MAX_GROUP_CHAT_MEMBERS,
            maximumAnnouncementChars = com.labteto.dshmobile.local.chat.MAX_GROUP_CHAT_ANNOUNCEMENT_CHARS,
        )
    }

    override fun submit(command: ChatGroupUiCommand): UiCommandReceipt = try {
        synchronized(store.foregroundRunHandle.lock) {
            val current = store.state.value
            if (store.sessionTransitioning) return@synchronized UiCommandReceipt.Rejected(
                UiCommandRejection.SESSION_TRANSITION)
            if (current.usageMode != LocalUsageMode.CHAT || current.loading ||
                store.uiBindingIdentity(current) != command.identity
            ) return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            when (command) {
                is ChatGroupUiCommand.SaveAnnouncement -> {
                    val result = chat.commitGroupAnnouncementFromUi(command.text)
                    if (result.isSuccess) UiCommandReceipt.Accepted
                    else UiCommandReceipt.Failed(result.exceptionOrNull()?.message ?: "群公告保存未完成")
                }
                is ChatGroupUiCommand.RemoveMember -> {
                    if (chat.removeGroupChatMemberByGalleryId(command.galleryId)) UiCommandReceipt.Accepted
                    else UiCommandReceipt.NotAdmitted
                }
                is ChatGroupUiCommand.SetGroupMode -> {
                    if (current.kernel.running) return@synchronized UiCommandReceipt.NotAdmitted
                    chat.switchChatMode(if (command.enabled) com.labteto.dshmobile.local.chat.LocalChatMode.GROUP
                        else com.labteto.dshmobile.local.chat.LocalChatMode.SINGLE)
                    UiCommandReceipt.Queued
                }
                is ChatGroupUiCommand.ConfigureMembers, is ChatGroupUiCommand.CreateGroupSession -> {
                    if (command is ChatGroupUiCommand.ConfigureMembers && !current.chat.groupChat.enabled)
                        return@synchronized UiCommandReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                    val ids = when (command) {
                        is ChatGroupUiCommand.ConfigureMembers -> command.galleryIds
                        is ChatGroupUiCommand.CreateGroupSession -> command.galleryIds
                        else -> error("Unreachable group member command")
                    }
                    val selected = ids.distinct()
                    if (selected.size !in com.labteto.dshmobile.local.chat.MIN_GROUP_CHAT_MEMBERS..com.labteto.dshmobile.local.chat.MAX_GROUP_CHAT_MEMBERS) return@synchronized UiCommandReceipt.Rejected(
                        UiCommandRejection.UNSUPPORTED)
                    val available = gallery.galleryEntries().associateBy { it.id }
                    if (selected.any { it !in available }) return@synchronized UiCommandReceipt.Rejected(
                        UiCommandRejection.UNSUPPORTED)
                    val entries = selected.map { available.getValue(it) }
                    if (command is ChatGroupUiCommand.CreateGroupSession) {
                        if (chat.createGroupChatSession(entries)) UiCommandReceipt.Accepted else UiCommandReceipt.NotAdmitted
                    } else if (chat.configureGroupChatMembers(entries)) UiCommandReceipt.Queued
                    else UiCommandReceipt.NotAdmitted
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { UiCommandReceipt.Failed(failure.message ?: "群聊操作未完成") }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalChatGroupUiBindings {
    @dagger.Binds abstract fun bindGroupPort(adapter: LocalChatGroupUiAdapter): ChatGroupUiPort
}
