package com.labteto.dshmobile.local.work

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResearchAgentPresetTest {
    @Test fun researchIdentityDemandsVerifiableSourcesAndUncertainty() {
        val instructions = LocalResearchAgentPreset.instructions
        assertTrue(instructions.contains("不得编造引用"))
        assertTrue(instructions.contains("交叉验证"))
        assertTrue(instructions.contains("证据局限"))
        assertTrue(instructions.contains("只读能力边界"))
        assertTrue(instructions.length < 4_000)
    }
}
