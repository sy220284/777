package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns direct-chat persona selection and gallery binding for ChatFeature. */
@Singleton
internal class LocalChatPersonaCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val personaStore: ChatPersonaStore,
    private val sessionStorage: LocalSessionStorageRuntime,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                runtimeStateStore.mutableState.update {
                    it.copy(error = error.message ?: "角色切换失败")
                }
            },
    )

    internal fun select(profile: PersonaProfile, galleryId: String? = null) {
        val snapshot = runtimeStateStore.state.value
        if (
            snapshot.kernel.running ||
            snapshot.loading ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.chat.groupChat.enabled ||
            snapshot.transcriptIndex.hasDialogue
        ) return

        scope.launch {
            val personaId = profile.id.takeUnless { it == PersonaProfile.DEFAULT_PERSONA_ID }
                ?: "persona-${UUID.randomUUID()}"
            val saved = personaStore.upsert(profile.copy(id = personaId))
            runtimeStateStore.mutableState.update { state ->
                if (state.sessionId != snapshot.sessionId) {
                    state
                } else {
                    state.copy(
                        chat = state.chat.copy(
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
            }
            if (runtimeStateStore.state.value.sessionId == snapshot.sessionId) {
                sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)
            }
        }
    }

    internal fun bindGallery(galleryId: String, galleryStoryId: String?) {
        val snapshot = runtimeStateStore.state.value
        if (
            snapshot.loading ||
            snapshot.kernel.running ||
            snapshot.usageMode != LocalUsageMode.CHAT
        ) return
        runtimeStateStore.mutableState.update { state ->
            if (state.sessionId != snapshot.sessionId) {
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
        if (runtimeStateStore.state.value.sessionId == snapshot.sessionId) {
            sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)
        }
    }

    internal fun clearGalleryBinding(
        expectedGalleryId: String,
        expectedStoryId: String? = null,
        keepCharacter: Boolean = false,
    ) {
        val snapshot = runtimeStateStore.state.value
        if (
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.chat.galleryId != expectedGalleryId
        ) return
        if (expectedStoryId != null && snapshot.chat.galleryStoryId != expectedStoryId) return

        runtimeStateStore.mutableState.update { state ->
            if (state.sessionId != snapshot.sessionId) {
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
        if (runtimeStateStore.state.value.sessionId == snapshot.sessionId) {
            sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)
        }
    }

    internal suspend fun syncDefault(profile: PersonaProfile): PersonaProfile {
        val snapshot = runtimeStateStore.state.value
        check(
            !snapshot.kernel.running &&
                !snapshot.loading &&
                snapshot.usageMode == LocalUsageMode.CHAT &&
                !snapshot.chat.groupChat.enabled
        ) {
            "当前状态暂时不能同步默认角色"
        }

        return withContext(Dispatchers.IO) {
            val saved = personaStore.upsert(
                profile.copy(id = PersonaProfile.DEFAULT_PERSONA_ID),
            )
            runtimeStateStore.mutableState.update { state ->
                if (state.sessionId != snapshot.sessionId) {
                    state
                } else {
                    state.copy(
                        chat = state.chat.copy(
                            personaId = PersonaProfile.DEFAULT_PERSONA_ID,
                            galleryId = null,
                            galleryStoryId = null,
                            gallerySaveSuppressedThrough =
                                state.transcriptIndex.latestCreatedAt.takeIf { it > 0L }
                                    ?: System.currentTimeMillis(),
                            chatPersona = saved,
                        ),
                    )
                }
            }
            if (runtimeStateStore.state.value.sessionId == snapshot.sessionId) {
                sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)
            }
            saved
        }
    }
}
