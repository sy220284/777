package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope

/** Stable relationship-memory owner. Gallery identity wins because one persona can be copied. */
internal fun chatRelationshipSubjectKey(
    galleryId: String?,
    personaId: String?,
): String? =
    galleryId?.takeIf(String::isNotBlank)?.let { "gallery:$it" }
        ?: personaId
            ?.takeIf { it.isNotBlank() && it != PersonaProfile.DEFAULT_PERSONA_ID }
            ?.let { "persona:$it" }

/**
 * Decide whether a relationship memory is allowed into the current character context.
 *
 * New records use [MemoryRecord.subjectKey]. Legacy records are accepted only when their lineage
 * matches or their text explicitly names the current character. User-wide relationship preferences
 * remain global by design.
 */
internal fun relationshipMemoryMatchesSubject(
    memory: MemoryRecord,
    currentSubjectKey: String?,
    currentLineageId: String?,
    subjectLabel: String?,
): Boolean {
    if (memory.kind == MemoryKind.RELATIONSHIP_PREFERENCE) return true
    if (memory.subjectKey != null) {
        return currentSubjectKey != null && memory.subjectKey == currentSubjectKey
    }
    if (memory.scope == MemoryScope.LINEAGE) {
        return currentLineageId != null && memory.lineageId == currentLineageId
    }

    val subject = subjectLabel?.trim()
        ?.takeIf { it.isNotBlank() && it != "默认角色" }
        ?: return false
    return memory.content.startsWith("关系状态：我和$subject｜") ||
        memory.content.startsWith("关系对象：$subject｜")
}
