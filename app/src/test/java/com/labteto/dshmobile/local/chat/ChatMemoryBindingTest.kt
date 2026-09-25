package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryBindingTest {
    @Test
    fun galleryIdentityWinsOverPersonaIdentity() {
        assertTrue(chatRelationshipSubjectKey("entry-1", "persona-1") == "gallery:entry-1")
        assertTrue(chatRelationshipSubjectKey(null, "persona-1") == "persona:persona-1")
        assertTrue(chatRelationshipSubjectKey(null, PersonaProfile.DEFAULT_PERSONA_ID) == null)
    }

    @Test
    fun boundRelationshipMemoryOnlyMatchesItsCharacter() {
        val memory = relationship(
            content = "关系状态：我和同名角色｜在一起",
            subjectKey = "gallery:one",
        )

        assertTrue(
            relationshipMemoryMatchesSubject(
                memory = memory,
                currentSubjectKey = "gallery:one",
                currentLineageId = "lineage-a",
                subjectLabel = "同名角色",
            ),
        )
        assertFalse(
            relationshipMemoryMatchesSubject(
                memory = memory,
                currentSubjectKey = "gallery:two",
                currentLineageId = "lineage-a",
                subjectLabel = "同名角色",
            ),
        )
    }

    @Test
    fun legacyGlobalMemoryMustExplicitlyNameCurrentCharacter() {
        val matching = relationship("关系状态：我和林晚｜在一起")
        val other = relationship("关系状态：我和顾言｜在一起")

        assertTrue(
            relationshipMemoryMatchesSubject(matching, "gallery:lin", "lineage", "林晚"),
        )
        assertFalse(
            relationshipMemoryMatchesSubject(other, "gallery:lin", "lineage", "林晚"),
        )
    }

    @Test
    fun globalRelationshipPreferenceRemainsUserWide() {
        val preference = relationship(
            content = "用户关系偏好：我不喜欢冷处理",
            kind = MemoryKind.RELATIONSHIP_PREFERENCE,
        )
        assertTrue(
            relationshipMemoryMatchesSubject(
                preference,
                currentSubjectKey = "gallery:any",
                currentLineageId = "any",
                subjectLabel = "任意角色",
            ),
        )
    }

    private fun relationship(
        content: String,
        subjectKey: String? = null,
        kind: MemoryKind = MemoryKind.RELATIONSHIP_STATE,
    ) = MemoryRecord(
        id = content,
        scope = MemoryScope.GLOBAL,
        kind = kind,
        content = content,
        subjectKey = subjectKey,
        importance = 86,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
