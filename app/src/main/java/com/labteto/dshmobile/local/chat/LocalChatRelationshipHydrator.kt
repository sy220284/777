package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Chat-owned restoration of a fresh visible relationship state from durable relationship memory. */
@Singleton
internal class LocalChatRelationshipHydrator @Inject constructor(
    private val chatState: LocalChatStatePort,
    private val memoryStore: MemoryStore,
    private val eventLogs: LocalSessionEventLogRegistry,
    private val sessionStorage: LocalSessionStorageRuntime,
) {
    internal fun hydrate() {
        val snapshot = chatState.value
        if (
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.chat.chatState.updatedAt != 0L
        ) return

        val aggregate = snapshot
        val subject = aggregate.chat.chatPersona.name.trim()
            .takeIf { it.isNotBlank() && it != "默认角色" }
            ?: return
        val prefix = "关系状态：我和$subject｜"
        val subjectKey = chatRelationshipSubjectKey(
            aggregate.chat.galleryId,
            aggregate.chat.personaId,
        )
        val latest = memoryStore.listActive(
            allowedScopes = setOf(MemoryScope.GLOBAL),
            projectId = null,
            lineageId = null,
            limit = 2_000,
        ).asSequence()
            .filter {
                it.kind == MemoryKind.RELATIONSHIP_STATE &&
                    relationshipMemoryMatchesSubject(
                        memory = it,
                        currentSubjectKey = subjectKey,
                        currentLineageId = null,
                        subjectLabel = aggregate.chat.chatPersona.name,
                    ) &&
                    it.content.startsWith(prefix)
            }
            .maxByOrNull { it.updatedAt }
            ?: return

        val stored = latest.content.substringAfter("｜", "").trim()
        val stage = when (stored) {
            "暧昧" -> "AMBIGUOUS"
            "在一起", "确定关系", "异地", "订婚", "结婚", "同居" -> "COMMITTED"
            "冷战" -> "CONFLICT"
            "分手", "离婚" -> "SEPARATED"
            "复合" -> "REPAIRING"
            else -> return
        }
        val label = when (stage) {
            "AMBIGUOUS" -> "暧昧期"
            "COMMITTED" -> "稳定关系"
            "CONFLICT" -> "矛盾期"
            "SEPARATED" -> "已分开"
            "REPAIRING" -> "修复中"
            else -> aggregate.chat.chatState.relationshipState
        }
        if (
            aggregate.chat.chatState.dynamics.stage == stage &&
            aggregate.chat.chatState.relationshipState == label
        ) return

        chatState.update { current ->
            if (
                current.sessionId != aggregate.sessionId ||
                current.chat.chatState.updatedAt != 0L
            ) {
                current
            } else {
                current.copy(
                    chat = current.chat.copy(
                        chatState = current.chat.chatState.copy(
                            relationshipState = label,
                            dynamics = current.chat.chatState.dynamics.copy(stage = stage),
                        ),
                    ),
                )
            }
        }
        eventLogs.get(aggregate.sessionId).append("chat/relationship-hydrate", buildJsonObject {
            put("subject", subject)
            put("subject_key", subjectKey.orEmpty())
            put("state", stored)
            put("stage", stage)
            put("memory_id", latest.id)
        })
        sessionStorage.enqueueCurrentSnapshot(aggregate.sessionId)
    }
}
