package com.labteto.dshmobile.local.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSkillMetadataTest {
    @Test
    fun parsesContractFieldsAndKeepsSkillIdentityFromFolder() {
        val skill = parseLocalSkillMetadata(
            "safe-audit",
            """
                ---
                name: arbitrary-display-name
                description: "Review code changes"
                when-to-use: For code review and analysis
                disable-model-invocation: true
                ---
                # Instructions
            """.trimIndent(),
        )
        assertEquals("safe-audit", skill.name)
        assertEquals("Review code changes", skill.description)
        assertEquals("For code review and analysis", skill.whenToUse)
        assertFalse(skill.modelInvocable)
    }

    @Test
    fun legacyAndMalformedMetadataPreserveExistingBehaviour() {
        assertTrue(parseLocalSkillMetadata("old", "# Normal markdown").modelInvocable)
        assertFalse(parseLocalSkillMetadata("missing-close", "---\ndisable-model-invocation: true").modelInvocable)
    }

    @Test
    fun falseDisableFlagKeepsModelInvocationAvailable() {
        val skill = parseLocalSkillMetadata("allowed", "---\ndisable-model-invocation: false\n---")
        assertTrue(skill.modelInvocable)
    }
}
