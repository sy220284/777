package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns direct-chat persona selection and gallery binding for ChatFeature. */
@Singleton
internal class LocalChatPersonaCoordinator internal constructor(
    private val state: MutableStateFlow<LocalHarnessState>,
    private val savePersona: suspend (PersonaProfile) -> PersonaProfile,
    private val persistNow: suspend (String) -> Boolean,
    private val enqueueSnapshot: (String) -> Boolean,
) {
    @Inject
    internal constructor(
        runtimeStateStore: LocalRuntimeStateStore,
        personaStore: ChatPersonaStore,
        sessionStorage: LocalSessionStorageRuntime,
    ) : this(
        state = runtimeStateStore.mutableState,
        savePersona = { profile -> withContext(Dispatchers.IO) { personaStore.upsert(profile) } },
        persistNow = sessionStorage::writeCurrentSnapshotNow,
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
                state.update { current ->
                    if (current.sessionId == snapshot.sessionId) {
                        current.copy(error = error.message ?: "角色切换失败")
                    } else current
                }
            }
        }
    }

    internal suspend fun selectNow(
        snapshot: LocalHarnessState,
        profile: PersonaProfile,
        galleryId: String? = null,
    ): Boolean {
        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId, LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return false
        return try {
            if (!state.value.matchesPersonaEdit(snapshot, requireEmptyDialogue = true)) return false
            val personaId = profile.id.takeUnless { it == PersonaProfile.DEFAULT_PERSONA_ID }
                ?: "persona-${UUID.randomUUID()}"
            val saved = savePersona(profile.copy(id = personaId))
            var applied = false
            state.update { current ->
                applied = current.matchesPersonaEdit(snapshot, requireEmptyDialogue = true)
                if (!applied) current else current.copy(
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
                )
            }
            if (!applied) return false
            check(persistNow(snapshot.sessionId)) { "会话已切换，请重新选择角色" }
            true
        } finally {
            lease.close()
        }
    }

    internal fun bindGallery(galleryId: String, galleryStoryId: String?) {
        val snapshot = state.value
        if (
            snapshot.loading ||
            snapshot.kernel.running ||
            snapshot.usageMode != LocalUsageMode.CHAT
        ) return
        state.update { state ->
            if (
                state.sessionId != snapshot.sessionId ||
                state.usageMode != LocalUsageMode.CHAT ||
                state.loading || state.kernel.running ||
                state.chat.personaId != snapshot.chat.personaId
            ) {
                state
            } else {
                state.copy(
                    chat = state.chat.copy(
                        galleryId = galleryId,
                        galleryStoryId = galleryStoryId,
                        gallerySaveSuppressedThrough = 0L,
                    ),
                )
            }
        }
        if (state.value.sessionId == snapshot.sessionId) {
            enqueueSnapshot(snapshot.sessionId)
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

        state.update { state ->
            if (
                state.sessionId != snapshot.sessionId ||
                state.usageMode != LocalUsageMode.CHAT ||
                state.chat.galleryId != expectedGalleryId ||
                (expectedStoryId != null && state.chat.galleryStoryId != expectedStoryId)
            ) {
                state
            } else {
                state.copy(
                    chat = state.chat.copy(
                        galleryId = if (keepCharacter) state.chat.galleryId else null,
                        galleryStoryId = null,
                        gallerySaveSuppressedThrough =
                            state.transcriptIndex.latestCreatedAt.takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
        if (state.value.sessionId == snapshot.sessionId) {
            enqueueSnapshot(snapshot.sessionId)
        }
    }

    internal suspend fun syncDefault(profile: PersonaProfile): PersonaProfile {
        val snapshot = state.value
        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            snapshot.sessionId, LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: error("当前会话正在运行，请稍后再同步默认角色")
        return try {
            check(state.value.matchesPersonaEdit(snapshot)) { "当前状态暂时不能同步默认角色" }
            val saved = savePersona(profile.copy(id = PersonaProfile.DEFAULT_PERSONA_ID))
            var applied = false
            state.update { current ->
                applied = current.matchesPersonaEdit(snapshot)
                if (!applied) current else current.copy(
                    chat = current.chat.copy(
                        personaId = PersonaProfile.DEFAULT_PERSONA_ID,
                        galleryId = null,
                        galleryStoryId = null,
                        gallerySaveSuppressedThrough =
                            current.transcriptIndex.latestCreatedAt.takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                        chatPersona = saved,
                    ),
                )
            }
            check(applied && persistNow(snapshot.sessionId)) { "会话已变化，请重新同步默认角色" }
            saved
        } finally {
            lease.close()
        }
    }
}

private fun LocalHarnessState.canEditPersona(requireEmptyDialogue: Boolean = false): Boolean =
    !kernel.running && !loading && usageMode == LocalUsageMode.CHAT &&
        !chat.groupChat.enabled && (!requireEmptyDialogue || !transcriptIndex.hasDialogue)

private fun LocalHarnessState.matchesPersonaEdit(
    before: LocalHarnessState,
    requireEmptyDialogue: Boolean = false,
): Boolean = canEditPersona(requireEmptyDialogue) && sessionId == before.sessionId &&
    chat.personaId == before.chat.personaId && chat.chatPersona == before.chat.chatPersona &&
    chat.galleryId == before.chat.galleryId && chat.galleryStoryId == before.chat.galleryStoryId &&
    chat.gallerySaveSuppressedThrough == before.chat.gallerySaveSuppressedThrough &&
    chat.chatContext.generation == before.chat.chatContext.generation &&
    transcriptIndex.latestDialogueMessageId == before.transcriptIndex.latestDialogueMessageId
