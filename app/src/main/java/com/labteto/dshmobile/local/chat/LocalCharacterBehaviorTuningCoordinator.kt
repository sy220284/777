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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Singleton
internal class LocalCharacterBehaviorTuningCoordinator internal constructor(
    private val state: LocalChatStatePort,
    private val personaStore: ChatPersonaStore,
    private val galleryStore: ChatPersonaGalleryStore,
    private val acquireLease: (String) -> AutoCloseable?,
    private val commitDomainState: (LocalChatProjectionState, String) -> Unit,
    private val enqueueSnapshot: (String) -> Boolean,
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
            LocalSessionRuntimeRegistry.tryAcquire(sessionId, LocalSessionRuntimeKind.MAINTENANCE)
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
            val previousPersona = withContext(Dispatchers.IO) { personaStore.find(personaId) }
            val previousGallery = withContext(Dispatchers.IO) {
                snapshot.chat.galleryId?.let(galleryStore::findEntry)
            }
            var committed = false
            try {
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
                val current = state.value
                check(
                    current.sessionId == snapshot.sessionId &&
                        current.usageMode == LocalUsageMode.CHAT &&
                        !current.loading &&
                        !current.kernel.running &&
                        !current.chat.groupChat.enabled &&
                        current.chat.personaId == snapshot.chat.personaId &&
                        current.chat.galleryId == snapshot.chat.galleryId &&
                        current.chat.galleryStoryId == snapshot.chat.galleryStoryId &&
                        current.chat.chatContext.generation == snapshot.chat.chatContext.generation &&
                        current.transcriptIndex.latestDialogueMessageId ==
                            snapshot.transcriptIndex.latestDialogueMessageId
                ) { "会话已切换，请重新保存角色设置" }

                val durablePersona = persisted.persona
                val sameBoundCharacter = persisted.sameBoundCharacter
                val target = current.copy(
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
                        chatState = if (sameBoundCharacter) {
                            current.chat.chatState.copy(
                                behaviorTuning = durablePersona.behaviorTuning,
                            )
                        } else {
                            ChatCharacterState(behaviorTuning = durablePersona.behaviorTuning)
                        },
                        replySuggestions =
                            if (sameBoundCharacter) current.chat.replySuggestions else emptyList(),
                    ),
                    handoffSummary = if (sameBoundCharacter) current.handoffSummary else null,
                    error = null,
                )
                commitDomainState(target, "behavior-tuning-updated")
                committed = true
                state.update { latest ->
                    if (latest.sessionId != target.sessionId) latest else latest.copy(
                        chat = target.chat,
                        handoffSummary = target.handoffSummary,
                        error = null,
                    )
                }
                enqueueSnapshot(snapshot.sessionId)
            } catch (error: Throwable) {
                if (!committed) {
                    rollbackStores(
                        personaId,
                        previousPersona,
                        snapshot.chat.galleryId,
                        previousGallery,
                        error,
                    )
                }
                throw error
            }
        } finally {
            lease.close()
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun rollbackStores(
        personaId: String,
        previousPersona: PersonaProfile?,
        galleryId: String?,
        previousGallery: PersonaGalleryEntry?,
        primary: Throwable,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { personaStore.restore(personaId, previousPersona) }
                .exceptionOrNull()
                ?.let { primary.addSuppressed(it) }
            galleryId?.let { id ->
                runCatching { galleryStore.restoreEntry(id, previousGallery) }
                    .exceptionOrNull()
                    ?.let { primary.addSuppressed(it) }
            }
        }
    }
}
