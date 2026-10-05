package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.model.groupChatSystemPrompt
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ChatFeature owner for group-member edits.
 *
 * Membership changes use the shared Session MAINTENANCE lease instead of Engine-private locks.
 * Model history and durable snapshot writes stay on the shared runtime/session authorities.
 */
@Singleton
internal class LocalGroupChatMembershipCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val personaStore: ChatPersonaStore,
    private val modelHistoryRuntime: LocalForegroundModelHistoryRuntime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun configure(entries: List<PersonaGalleryEntry>): Boolean {
        val snapshot = runtimeStateStore.state.value
        if (
            snapshot.loading ||
            snapshot.kernel.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            !snapshot.chat.groupChat.enabled
        ) return false

        val selected = entries
            .distinctBy(PersonaGalleryEntry::id)
            .take(MAX_GROUP_CHAT_MEMBERS)
        if (selected.size !in MIN_GROUP_CHAT_MEMBERS..MAX_GROUP_CHAT_MEMBERS) return false

        scope.launch {
            try {
                val members = resolveLocalGroupChatMembers(
                entries = selected,
                previousMembers = snapshot.chat.groupChat.members,
                chatPersonaStore = personaStore,
            )
            val lease = LocalSessionRuntimeRegistry.tryAcquire(
                snapshot.sessionId,
                LocalSessionRuntimeKind.MAINTENANCE,
            ) ?: return@launch
            try {
                var applied = false
                runtimeStateStore.mutableState.update { current ->
                    applied =
                        current.sessionId == snapshot.sessionId &&
                            current.usageMode == LocalUsageMode.CHAT &&
                            !current.loading &&
                            !current.kernel.running &&
                            current.chat.groupChat == snapshot.chat.groupChat &&
                            current.transcriptIndex.latestDialogueMessageId ==
                                snapshot.transcriptIndex.latestDialogueMessageId
                    if (!applied) current else current.copy(
                        chat = current.chat.copy(
                            groupChat = current.chat.groupChat.copy(members = members),
                            replySuggestions = emptyList(),
                            chatBranches = LocalChatBranchState(),
                        ),
                        error = null,
                    )
                }
                if (!applied) return@launch

                val eventLog = sessionStorage.eventLogs.get(snapshot.sessionId)
                modelHistoryRuntime.replaceSystemPrompt(snapshot.sessionId, groupChatSystemPrompt())
                modelHistoryRuntime.checkpoint(snapshot.sessionId, "group/members-updated")
                eventLog.append("group/members", buildJsonObject {
                    put("count", members.size)
                    put("gallery_ids", JsonArray(members.map { JsonPrimitive(it.galleryId) }))
                })
                sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)
            } finally {
                lease.close()
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runtimeStateStore.mutableState.update { current ->
                    if (current.sessionId == snapshot.sessionId && current.usageMode == snapshot.usageMode) {
                        current.copy(
                            error = error.message?.takeIf(String::isNotBlank)
                                ?: "群聊成员更新失败：${error::class.java.simpleName}",
                        )
                    } else current
                }
            }
        }
        return true
    }

    internal fun remove(galleryId: String) {
        val snapshot = runtimeStateStore.state.value
        if (
            snapshot.loading ||
            snapshot.kernel.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            !snapshot.chat.groupChat.enabled ||
            snapshot.chat.groupChat.members.none { it.galleryId == galleryId }
        ) return

        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return
        try {
            var applied = false
            var memberCount = 0
            runtimeStateStore.mutableState.update { current ->
                applied =
                    current.sessionId == snapshot.sessionId &&
                        current.usageMode == LocalUsageMode.CHAT &&
                        !current.loading &&
                        !current.kernel.running &&
                        current.chat.groupChat == snapshot.chat.groupChat &&
                        current.transcriptIndex.latestDialogueMessageId ==
                            snapshot.transcriptIndex.latestDialogueMessageId
                if (!applied) current else {
                    val members = current.chat.groupChat.members.filterNot {
                        it.galleryId == galleryId
                    }
                    memberCount = members.size
                    current.copy(
                        chat = current.chat.copy(
                            groupChat = current.chat.groupChat.copy(
                                members = members,
                                turnCursor = 0,
                            ),
                            groupActiveSpeakerName = null,
                        ),
                        error = null,
                    )
                }
            }
            if (!applied) return

            val eventLog = sessionStorage.eventLogs.get(snapshot.sessionId)
            modelHistoryRuntime.replaceSystemPrompt(snapshot.sessionId, groupChatSystemPrompt())
            modelHistoryRuntime.checkpoint(snapshot.sessionId, "group/member-deleted")
            eventLog.append("group/members", buildJsonObject {
                put("action", "member-deleted")
                put("gallery_id", galleryId)
                put("count", memberCount)
            })
            sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)
        } finally {
            lease.close()
        }
    }


}
