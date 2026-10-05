package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.PERSONA_CORRECTION_UNDO_MILLIS
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns explicit persona corrections and undo.
 *
 * PersonaStore is the durable persona authority; Session Event records the conversation-local audit
 * trail. The visible notice is a bounded Chat projection and expires without involving Engine.
 */
@Singleton
internal class LocalChatPersonaCorrectionCoordinator @Inject constructor(
    private val chatState: LocalChatStatePort,
    private val personaStore: ChatPersonaStore,
    private val eventLogs: LocalSessionEventLogRegistry,
    private val sessionStorage: LocalSessionStorageRuntime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun captureDirect(text: String) {
        val snapshot = chatState.value
        if (snapshot.usageMode != LocalUsageMode.CHAT || text.isBlank()) return
        val boundLog = eventLogs.get(snapshot.sessionId)
        val updated = personaStore.captureExplicitCorrection(snapshot.chat.personaId, text) ?: return
        if (updated.corrections == snapshot.chat.chatPersona.corrections) return

        val correction = updated.corrections.lastOrNull().orEmpty()
        val notice = ChatPersonaCorrectionNotice(
            id = System.nanoTime(),
            personaId = updated.id,
            correction = correction,
        )
        chatState.update { current ->
            if (
                current.sessionId == snapshot.sessionId &&
                current.chat.personaId == updated.id
            ) {
                current.copy(
                    chat = current.chat.copy(
                        chatPersona = updated,
                        personaCorrectionNotice = notice,
                    ),
                )
            } else current
        }
        boundLog.append("chat/persona-correction", buildJsonObject {
            put("persona_id", updated.id)
            put("count", updated.corrections.size)
            put("latest", correction)
        })
        sessionStorage.enqueueCurrentSnapshot(snapshot.sessionId)

        scope.launch {
            delay(PERSONA_CORRECTION_UNDO_MILLIS)
            chatState.update { current ->
                if (
                    current.sessionId == snapshot.sessionId &&
                    current.chat.personaCorrectionNotice?.id == notice.id
                ) {
                    current.copy(
                        chat = current.chat.copy(personaCorrectionNotice = null),
                    )
                } else current
            }
        }
    }

    internal fun captureGroup(text: String) {
        val snapshot = chatState.value
        if (
            snapshot.usageMode != LocalUsageMode.CHAT ||
            !snapshot.chat.groupChat.enabled ||
            text.isBlank()
        ) return
        val boundLog = eventLogs.get(snapshot.sessionId)

        snapshot.chat.groupChat.members.forEach { member ->
            val currentPersona = personaStore.get(member.personaId)
            if (currentPersona.name.isBlank() || currentPersona.name !in text) return@forEach
            val updated = personaStore.captureExplicitCorrection(member.personaId, text)
                ?: return@forEach
            if (updated.corrections == currentPersona.corrections) return@forEach

            chatState.update { current ->
                if (current.sessionId != snapshot.sessionId) {
                    current
                } else {
                    current.copy(
                        chat = current.chat.copy(
                            groupChat = current.chat.groupChat.copy(
                                members = current.chat.groupChat.members.map { existing ->
                                    if (existing.galleryId == member.galleryId) {
                                        existing.copy(displayName = updated.name)
                                    } else existing
                                },
                            ),
                        ),
                    )
                }
            }
            boundLog.append("group/persona-correction", buildJsonObject {
                put("gallery_id", member.galleryId)
                put("persona_id", member.personaId)
                put("count", updated.corrections.size)
                put("latest", updated.corrections.lastOrNull().orEmpty())
            })
        }
    }

    internal suspend fun undo(
        noticeId: Long,
        personaId: String,
        correction: String,
    ) {
        val snapshot = chatState.value
        val notice = snapshot.chat.personaCorrectionNotice
        if (
            notice == null ||
            notice.id != noticeId ||
            notice.personaId != personaId ||
            notice.correction != correction
        ) return

        val boundEventLog = eventLogs.get(snapshot.sessionId)
        val updated = withContext(Dispatchers.IO) {
            personaStore.removeCorrection(personaId, correction)
        } ?: return

        chatState.update { current ->
            if (
                current.sessionId == snapshot.sessionId &&
                current.chat.personaId == personaId &&
                current.chat.personaCorrectionNotice?.id == noticeId
            ) {
                current.copy(
                    chat = current.chat.copy(
                        chatPersona = updated,
                        personaCorrectionNotice = null,
                    ),
                )
            } else {
                current
            }
        }
        boundEventLog.append("chat/persona-correction", buildJsonObject {
            put("persona_id", personaId)
            put("action", "undo")
            put("correction", correction)
        })
    }
}
