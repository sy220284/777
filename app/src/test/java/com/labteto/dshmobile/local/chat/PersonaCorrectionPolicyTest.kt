package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonaCorrectionPolicyTest {
    @Test
    fun explicitCorrectionExtractsOnlyCorrectionBody() {
        assertEquals(
            "她说话不会每句都带波浪号",
            extractPersonaCorrection("人设纠正：她说话不会每句都带波浪号"),
        )
    }

    @Test
    fun naturalCorrectionIsAccepted() {
        assertEquals(
            "你不会这么说，你平时会先损我一句",
            extractPersonaCorrection("你不会这么说，你平时会先损我一句"),
        )
    }

    @Test
    fun ordinaryChatIsNotPersistedAsPersonaCorrection() {
        assertNull(extractPersonaCorrection("你今天怎么这么开心"))
        assertNull(extractPersonaCorrection("她今天没有回我"))
        assertNull(extractPersonaCorrection("这个角色挺有意思的"))
    }
}
