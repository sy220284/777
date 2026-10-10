package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
internal class LocalChatPersonaCoordinator internal constructor(
    private val state: LocalChatStatePort,
    private val loadPersona: suspend (String) -> PersonaProfile?,
    private val savePersona: suspend (PersonaProfile) -> PersonaProfile,
    private val restorePersona: suspend (String, PersonaProfile?) -> Unit,
    private val commitDomainState: (LocalChatProjectionState, String) -> Unit,
    private val enqueueSnapshot: (String) -> Boolean,
) {
    @Inject
    internal constructor(
        chatState: LocalChatStatePort,
        personaStore: ChatPersonaStore,
        sessionStorage: LocalSessionStorageRuntime,
    ) : this(
        state = chatState,
        loadPersona = { id -> withContext(Dispatchers.IO) { personaStore.find(id) } },
        savePersona = { profile -> withContext(Dispatchers.IO) { personaStore.upsert(profile) } },
        restorePersona = { id, previous ->
            withContext(Dispatchers.IO) { personaStore.restore(id, previous) }
        },
        commitDomainState = { committed, reason ->
            appendChatDomainStateCommit(
                sessionStorage.eventLogs.get(committed.sessionId),
                committed,
                reason,
            )
        },
        enqueueSnapshot = sessionStorage::enqueueCurrentSnapshot,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun select(profile: PersonaProfile, galleryId: String? = null) {
        val snapshot = state.value
        if (!snapshot.canEditPersona(requireEmptyDialogue = true)) return
        scope.launch {
            try {
                selectNow(snapshot, profile, galleryId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publishError(snapshot.sessionId, error.message ?: "角色切换失败")
            }
        }
    }

    internal suspend fun selectNow(
        snapshot: LocalChatProjectionState,
        profile: PersonaProfile,
        galleryId: String? = null,
    ): Boolean {
        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return false
        return try {
            if (!state.value.matchesPersonaEdit(snapshot, requireEmptyDialogue = true)) return false
            val personaId = profile.id.takeUnless { it == PersonaProfile.DEFAULT_PERSONA_ID }
                ?: "persona-${UUID.randomUUID()}"
            val previous = loadPersona(personaId)
            var committed = false
            try {
                val saved = savePersona(profile.copy(id = personaId))
                val current = state.value
                if (!current.matchesPersonaEdit(snapshot, requireEmptyDialogue = true)) {
                    rollbackPersona(personaId, previous, null)
                    return false
                }
                val target = current.copy(
                    chat = current.chat.copy(
                        personaId = saved.id,
                        galleryId = galleryId,
                        galleryStoryId = null,
                        gallerySaveSuppressedThrough = 0L,
                        chatPersona = saved,
                        chatState = ChatCharacterState(behaviorTuning = saved.behaviorTuning),
                        replySuggestions = emptyList(),
                        chatBranches = LocalChatBranchState(),
                    ),
                    handoffSummary = null,
                    error = null,
                )
                commitDomainState(target, "persona-selected")
                committed = true
                publishCommitted(target)
                enqueueSnapshot(snapshot.sessionId)
                true
            } catch (error: Throwable) {
                if (!committed) rollbackPersona(personaId, previous, error)
                throw error
            }
        } finally {
            lease.close()
        }
    }

    internal fun bindGallery(galleryId: String, galleryStoryId: String?) {
        val snapshot = state.value
        if (snapshot.loading || snapshot.kernel.running || snapshot.usageMode != LocalUsageMode.CHAT) return
        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return
        try {
            val current = state.value
            if (
                current.sessionId != snapshot.sessionId ||
                current.usageMode != LocalUsageMode.CHAT ||
                current.loading ||
                current.kernel.running ||
                current.chat.personaId != snapshot.chat.personaId ||
                current.chat.galleryId != snapshot.chat.galleryId ||
                current.chat.galleryStoryId != snapshot.chat.galleryStoryId
            ) return
            val target = current.copy(
                chat = current.chat.copy(
                    galleryId = galleryId,
                    galleryStoryId = galleryStoryId,
                    gallerySaveSuppressedThrough = 0L,
                ),
                error = null,
            )
            commitDomainState(target, "gallery-bound")
            publishCommitted(target)
            enqueueSnapshot(snapshot.sessionId)
        } catch (error: Throwable) {
            publishError(snapshot.sessionId, error.message ?: "人物图集绑定失败")
        } finally {
            lease.close()
        }
    }

    /** Apply a saved gallery story-stage to the currently bound, idle chat session. */
    internal fun updateBoundStoryStage(galleryId: String, storyId: String, stage: String): Boolean {
        val snapshot = state.value
        if (snapshot.usageMode != LocalUsageMode.CHAT || snapshot.loading ||
            snapshot.kernel.running || snapshot.chat.groupChat.enabled ||
            snapshot.chat.galleryId != galleryId || snapshot.chat.galleryStoryId != storyId
        ) return false
        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId, LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return false
        return try {
            val current = state.value
            if (current.sessionId != snapshot.sessionId ||
                current.usageMode != LocalUsageMode.CHAT || current.loading ||
                current.kernel.running || current.chat.galleryId != galleryId ||
                current.chat.galleryStoryId != storyId ||
                current.chat.chatContext.generation != snapshot.chat.chatContext.generation
            ) return false
            val target = current.copy(
                chat = current.chat.copy(
                    chatContext = current.chat.chatContext.withStoryStageSelection(stage),
                ),
            )
            commitDomainState(target, "gallery-story-stage-updated")
            publishCommitted(target)
            enqueueSnapshot(snapshot.sessionId)
            true
        } catch (error: Exception) {
            publishError(snapshot.sessionId, error.message ?: "当前会话剧情阶段同步失败")
            false
        } finally {
            lease.close()
        }
    }

    internal fun clearGalleryBinding(
        expectedGalleryId: String,
        expectedStoryId: String? = null,
        keepCharacter: Boolean = false,
    ) {
        val snapshot = state.value
        if (
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.chat.galleryId != expectedGalleryId
        ) return
        if (expectedStoryId != null && snapshot.chat.galleryStoryId != expectedStoryId) return

        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return
        try {
            val current = state.value
            if (
                current.sessionId != snapshot.sessionId ||
                current.usageMode != LocalUsageMode.CHAT ||
                current.loading ||
                current.kernel.running ||
                current.chat.galleryId != expectedGalleryId ||
                (expectedStoryId != null && current.chat.galleryStoryId != expectedStoryId)
            ) return
            val target = current.copy(
                chat = current.chat.copy(
                    galleryId = if (keepCharacter) current.chat.galleryId else null,
                    galleryStoryId = null,
                    gallerySaveSuppressedThrough =
                        current.transcriptIndex.latestCreatedAt.takeIf { it > 0L }
                            ?: System.currentTimeMillis(),
                ),
                error = null,
            )
            commitDomainState(target, "gallery-unbound")
            publishCommitted(target)
            enqueueSnapshot(snapshot.sessionId)
        } catch (error: Throwable) {
            publishError(snapshot.sessionId, error.message ?: "人物图集解绑失败")
        } finally {
            lease.close()
        }
    }

    internal suspend fun syncDefault(profile: PersonaProfile): PersonaProfile {
        val snapshot = state.value
        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: error("当前会话正在运行，请稍后再同步默认角色")
        return try {
            check(state.value.matchesPersonaEdit(snapshot)) { "当前状态暂时不能同步默认角色" }
            val personaId = PersonaProfile.DEFAULT_PERSONA_ID
            val previous = loadPersona(personaId)
            var committed = false
            try {
                val saved = savePersona(profile.copy(id = personaId))
                val current = state.value
                check(current.matchesPersonaEdit(snapshot)) { "会话已变化，请重新同步默认角色" }
                val target = current.copy(
                    chat = current.chat.copy(
                        personaId = personaId,
                        galleryId = null,
                        galleryStoryId = null,
                        gallerySaveSuppressedThrough =
                            current.transcriptIndex.latestCreatedAt.takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                        chatPersona = saved,
                    ),
                    error = null,
                )
                commitDomainState(target, "default-persona-synced")
                committed = true
                publishCommitted(target)
                enqueueSnapshot(snapshot.sessionId)
                saved
            } catch (error: Throwable) {
                if (!committed) rollbackPersona(personaId, previous, error)
                throw error
            }
        } finally {
            lease.close()
        }
    }

    private fun publishCommitted(target: LocalChatProjectionState) {
        state.update { current ->
            if (current.sessionId != target.sessionId) current else current.copy(
                chat = target.chat,
                handoffSummary = target.handoffSummary,
                error = target.error,
            )
        }
    }

    private fun publishError(sessionId: String, message: String) {
        state.update { current ->
            if (current.sessionId == sessionId) current.copy(error = message) else current
        }
    }

    private suspend fun rollbackPersona(
        personaId: String,
        previous: PersonaProfile?,
        primary: Throwable?,
    ) {
        withContext(NonCancellable) {
            runCatching { restorePersona(personaId, previous) }
                .exceptionOrNull()
                ?.let { rollbackError -> primary?.addSuppressed(rollbackError) }
        }
    }
}

private fun LocalChatProjectionState.canEditPersona(requireEmptyDialogue: Boolean = false): Boolean =
    !kernel.running && !loading && usageMode == LocalUsageMode.CHAT &&
        !chat.groupChat.enabled && (!requireEmptyDialogue || !transcriptIndex.hasDialogue)

private fun LocalChatProjectionState.matchesPersonaEdit(
    before: LocalChatProjectionState,
    requireEmptyDialogue: Boolean = false,
): Boolean = canEditPersona(requireEmptyDialogue) && sessionId == before.sessionId &&
    chat.personaId == before.chat.personaId && chat.chatPersona == before.chat.chatPersona &&
    chat.galleryId == before.chat.galleryId && chat.galleryStoryId == before.chat.galleryStoryId &&
    chat.gallerySaveSuppressedThrough == before.chat.gallerySaveSuppressedThrough &&
    chat.chatContext.generation == before.chat.chatContext.generation &&
    transcriptIndex.latestDialogueMessageId == before.transcriptIndex.latestDialogueMessageId
