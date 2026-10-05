package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns undo of an explicit persona correction.
 *
 * PersonaStore is the durable fact source. The correction notice is transient UI state, so no
 * Session snapshot write is required. The originating Session log is captured before IO so a
 * concurrent navigation cannot redirect the audit event into a different conversation.
 */
@Singleton
internal class LocalChatPersonaCorrectionCoordinator @Inject constructor(
    private val chatState: LocalChatStatePort,
    private val personaStore: ChatPersonaStore,
    private val eventLogs: LocalSessionEventLogRegistry,
) {
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
