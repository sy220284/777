package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns durable character/persona tuning edits inside ChatFeature.
 *
 * The edit takes the current Session MAINTENANCE lease before touching persona/gallery files and
 * commits the matching Session snapshot through the shared Session storage capability. No Engine
 * callback or second transition lock participates in the transaction.
 */
@Singleton
internal class LocalCharacterBehaviorTuningCoordinator internal constructor(
    private val state: LocalChatStatePort,
    private val personaStore: ChatPersonaStore,
    private val galleryStore: ChatPersonaGalleryStore,
    private val acquireLease: (String) -> AutoCloseable?,
    private val persistNow: suspend (String) -> Boolean,
) {
    @Inject
    internal constructor(
        chatState: LocalChatStatePort,
        personaStore: ChatPersonaStore,
        galleryStore: ChatPersonaGalleryStore,
        sessionStorage: LocalSessionStorageRuntime,
    ) : this(
        state = chatState,
        personaStore = personaStore,
        galleryStore = galleryStore,
        acquireLease = { sessionId ->
            LocalSessionRuntimeRegistry.tryAcquire(
                sessionId,
                LocalSessionRuntimeKind.MAINTENANCE,
            )
        },
        persistNow = sessionStorage::writeCurrentSnapshotNow,
    )

    suspend fun configure(profile: PersonaProfile): Result<Unit> = try {
        val admission = state.value
        val lease = acquireLease(admission.sessionId)
            ?: return Result.failure(IllegalStateException("当前会话正在运行，请稍后再保存角色设置"))
        try {
            val snapshot = state.value
            check(
                snapshot.sessionId == admission.sessionId &&
                    !snapshot.kernel.running &&
                    !snapshot.loading &&
                    snapshot.usageMode == LocalUsageMode.CHAT &&
                    !snapshot.chat.groupChat.enabled
            ) { "请在单人聊天空闲时保存角色设置" }

            val personaId = snapshot.chat.personaId.takeUnless {
                it == PersonaProfile.DEFAULT_PERSONA_ID
            } ?: "persona-${UUID.randomUUID()}"

            val persisted = withContext(Dispatchers.IO) {
                persistCharacterBehaviorTuning(
                    personaStore,
                    galleryStore,
                    snapshot.chat.chatPersona,
                    personaId,
                    snapshot.chat.galleryId,
                    profile,
                )
            }
            val durablePersona = persisted.persona
            val sameBoundCharacter = persisted.sameBoundCharacter
            state.update { current ->
                check(
                    current.sessionId == snapshot.sessionId &&
                        current.chat.personaId == snapshot.chat.personaId &&
                        current.chat.galleryId == snapshot.chat.galleryId
                ) { "会话已切换，请重新保存角色设置" }
                current.copy(
                    chat = current.chat.copy(
                        personaId = durablePersona.id,
                        galleryId = current.chat.galleryId.takeIf { sameBoundCharacter },
                        galleryStoryId = current.chat.galleryStoryId.takeIf { sameBoundCharacter },
                        gallerySaveSuppressedThrough =
                            if (sameBoundCharacter) {
                                current.chat.gallerySaveSuppressedThrough
                            } else {
                                current.transcriptIndex.latestCreatedAt.takeIf { it > 0L }
                                    ?: System.currentTimeMillis()
                            },
                        chatPersona = durablePersona,
                        chatState =
                            if (sameBoundCharacter) {
                                current.chat.chatState.copy(
                                    behaviorTuning = durablePersona.behaviorTuning,
                                )
                            } else {
                                ChatCharacterState(
                                    behaviorTuning = durablePersona.behaviorTuning,
                                )
                            },
                        replySuggestions =
                            if (sameBoundCharacter) current.chat.replySuggestions else emptyList(),
                    ),
                    handoffSummary = if (sameBoundCharacter) current.handoffSummary else null,
                )
            }
            check(persistNow(snapshot.sessionId)) { "会话已切换，请重新保存角色设置" }
        } finally {
            lease.close()
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
