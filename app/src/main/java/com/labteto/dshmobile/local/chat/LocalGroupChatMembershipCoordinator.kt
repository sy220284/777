package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.model.groupChatSystemPrompt
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
internal class LocalGroupChatMembershipCoordinator @Inject constructor(
    private val chatState: LocalChatStatePort,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val personaStore: ChatPersonaStore,
    private val modelHistoryRuntime: LocalForegroundModelHistoryRuntime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun configure(entries: List<PersonaGalleryEntry>): Boolean {
        val snapshot = chatState.value
        if (!snapshot.canEditGroupMembers()) return false
        val selected = entries.distinctBy(PersonaGalleryEntry::id).take(MAX_GROUP_CHAT_MEMBERS)
        if (selected.size !in MIN_GROUP_CHAT_MEMBERS..MAX_GROUP_CHAT_MEMBERS) return false

        scope.launch {
            val lease = LocalSessionRuntimeRegistry.tryAcquire(
                snapshot.sessionId,
                LocalSessionRuntimeKind.MAINTENANCE,
            ) ?: return@launch
            var committed = false
            var previousPersonas: Map<String, PersonaProfile?> = emptyMap()
            try {
                val current = chatState.value
                if (!current.matchesGroupMemberEdit(snapshot)) return@launch

                previousPersonas = selected.associate { entry ->
                    entry.id to personaStore.find(entry.id)
                }
                val members = resolveLocalGroupChatMembers(
                    entries = selected,
                    previousMembers = snapshot.chat.groupChat.members,
                    chatPersonaStore = personaStore,
                )
                val afterResolve = chatState.value
                if (!afterResolve.matchesGroupMemberEdit(snapshot)) {
                    rollbackPersonas(previousPersonas, null)
                    return@launch
                }

                val target = afterResolve.copy(
                    chat = afterResolve.chat.copy(
                        groupChat = afterResolve.chat.groupChat.copy(members = members),
                        replySuggestions = emptyList(),
                        chatBranches = LocalChatBranchState(),
                    ),
                    error = null,
                )
                val eventLog = sessionStorage.eventLogs.get(snapshot.sessionId)
                appendChatDomainStateCommit(eventLog, target, "group-members-updated")
                committed = true
                publishCommitted(target)
                sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)
                recordDerivedGroupState(
                    snapshot.sessionId,
                    eventLog,
                    "group/members-updated",
                    buildJsonObject {
                        put("count", members.size)
                        put("gallery_ids", JsonArray(members.map { JsonPrimitive(it.galleryId) }))
                    },
                )
            } catch (cancelled: CancellationException) {
                if (!committed) rollbackPersonas(previousPersonas, cancelled)
                throw cancelled
            } catch (error: Throwable) {
                if (!committed) rollbackPersonas(previousPersonas, error)
                publishError(
                    snapshot.sessionId,
                    error.message?.takeIf(String::isNotBlank)
                        ?: "群聊成员更新失败：${error::class.java.simpleName}",
                )
            } finally {
                lease.close()
            }
        }
        return true
    }

    internal fun remove(galleryId: String) {
        val snapshot = chatState.value
        if (
            !snapshot.canEditGroupMembers() ||
            snapshot.chat.groupChat.members.none { it.galleryId == galleryId }
        ) return

        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return
        try {
            val current = chatState.value
            if (!current.matchesGroupMemberEdit(snapshot)) return
            val members = current.chat.groupChat.members.filterNot { it.galleryId == galleryId }
            val target = current.copy(
                chat = current.chat.copy(
                    groupChat = current.chat.groupChat.copy(members = members, turnCursor = 0),
                    groupActiveSpeakerName = null,
                ),
                error = null,
            )
            val eventLog = sessionStorage.eventLogs.get(snapshot.sessionId)
            appendChatDomainStateCommit(eventLog, target, "group-member-deleted")
            publishCommitted(target)
            sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)
            recordDerivedGroupState(
                snapshot.sessionId,
                eventLog,
                "group/member-deleted",
                buildJsonObject {
                    put("action", "member-deleted")
                    put("gallery_id", galleryId)
                    put("count", members.size)
                },
            )
        } catch (error: Throwable) {
            publishError(
                snapshot.sessionId,
                error.message?.takeIf(String::isNotBlank)
                    ?: "群聊成员删除失败：${error::class.java.simpleName}",
            )
        } finally {
            lease.close()
        }
    }

    private fun publishCommitted(target: LocalChatProjectionState) {
        chatState.update { current ->
            if (current.sessionId != target.sessionId) current else current.copy(
                chat = target.chat,
                error = target.error,
            )
        }
    }

    private fun recordDerivedGroupState(
        sessionId: String,
        eventLog: com.labteto.dshmobile.local.session.LocalSessionEventLog,
        reason: String,
        audit: JsonObject,
    ) {
        runCatching {
            check(modelHistoryRuntime.replaceSystemPrompt(sessionId, groupChatSystemPrompt())) {
                "群聊模型历史会话已变化"
            }
            modelHistoryRuntime.checkpoint(sessionId, reason)
            eventLog.append("group/members", audit)
        }.onFailure { error ->
            publishError(
                sessionId,
                "群聊成员已保存，恢复投影写入失败：${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    private suspend fun rollbackPersonas(
        previous: Map<String, PersonaProfile?>,
        primary: Throwable?,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            previous.forEach { (id, profile) ->
                runCatching { personaStore.restore(id, profile) }
                    .exceptionOrNull()
                    ?.let { primary?.addSuppressed(it) }
            }
        }
    }

    private fun publishError(sessionId: String, message: String) {
        chatState.update { current ->
            if (current.sessionId == sessionId) current.copy(error = message) else current
        }
    }
}

private fun LocalChatProjectionState.canEditGroupMembers(): Boolean =
    !loading && !kernel.running && usageMode == LocalUsageMode.CHAT && chat.groupChat.enabled

private fun LocalChatProjectionState.matchesGroupMemberEdit(
    before: LocalChatProjectionState,
): Boolean =
    canEditGroupMembers() &&
        sessionId == before.sessionId &&
        chat.groupChat == before.chat.groupChat &&
        transcriptIndex.latestDialogueMessageId == before.transcriptIndex.latestDialogueMessageId
