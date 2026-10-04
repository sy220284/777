package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the durable persona edit; the shared session transition lock stays authoritative. */
internal class LocalCharacterBehaviorTuningCoordinator(
    private val state: MutableStateFlow<LocalHarnessState>,
    private val personaStore: ChatPersonaStore,
    private val galleryStore: ChatPersonaGalleryStore,
    private val transitionMutex: Mutex,
    private val persistNow: suspend () -> Unit,
) {
    suspend fun configure(profile: PersonaProfile): Result<Unit> = try {
        transitionMutex.withLock {
            val snapshot = state.value
            check(
                !snapshot.kernel.running && !snapshot.loading &&
                    snapshot.usageMode == LocalUsageMode.CHAT && !snapshot.chat.groupChat.enabled
            ) { "请在单人聊天空闲时保存角色设置" }
            val personaId = snapshot.chat.personaId.takeUnless {
                it == PersonaProfile.DEFAULT_PERSONA_ID
            } ?: "persona-${UUID.randomUUID()}"
            val persisted = withContext(Dispatchers.IO) {
                persistCharacterBehaviorTuning(
                    personaStore, galleryStore, snapshot.chat.chatPersona,
                    personaId, snapshot.chat.galleryId, profile,
                )
            }
            val durablePersona = persisted.persona
            val sameBoundCharacter = persisted.sameBoundCharacter
            state.update { state ->
                check(state.sessionId == snapshot.sessionId && state.chat.personaId == snapshot.chat.personaId &&
                    state.chat.galleryId == snapshot.chat.galleryId) { "会话已切换，请重新保存角色设置" }
                state.copy(
                    chat = state.chat.copy(
                        personaId = durablePersona.id,
                        galleryId = state.chat.galleryId.takeIf { sameBoundCharacter },
                        galleryStoryId = state.chat.galleryStoryId.takeIf { sameBoundCharacter },
                        gallerySaveSuppressedThrough = if (sameBoundCharacter) state.chat.gallerySaveSuppressedThrough
                        else state.transcriptIndex.latestCreatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        chatPersona = durablePersona,
                        chatState = if (sameBoundCharacter) {
                            state.chat.chatState.copy(behaviorTuning = durablePersona.behaviorTuning)
                        } else ChatCharacterState(behaviorTuning = durablePersona.behaviorTuning),
                        replySuggestions = if (sameBoundCharacter) state.chat.replySuggestions else emptyList(),
                    ),
                    handoffSummary = if (sameBoundCharacter) state.handoffSummary else null,
                )
            }
            persistNow()
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

}
