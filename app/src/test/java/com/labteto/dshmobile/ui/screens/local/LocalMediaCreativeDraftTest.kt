package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.model.LocalMediaKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaCreativeDraftTest {
    @Test fun ttsExcludesActionsButKeepsOnlyAudibleLines() {
        val reply = "（轻轻点头）好呀。\n(望向窗外)我也想去。\n*微笑*下次见。"
        assertEquals("好呀。\n我也想去。\n下次见。",
            mediaCreativeDraft(LocalMediaKind.SPEECH, reply, "绫华", ""))
    }

    @Test fun imageDraftCarriesKnownIdentityAndNoTextRule() {
        val prompt = mediaCreativeDraft(LocalMediaKind.IMAGE, "（微笑）今天很开心。", "绫华", "稻妻少女")
        assertTrue(prompt.contains("绫华"))
        assertTrue(prompt.contains("稻妻少女"))
        assertTrue(prompt.contains("对话框"))
        assertFalse(prompt.contains("姓名不详"))
    }

    @Test fun transcriptionDoesNotReceiveChatText() {
        assertEquals("", mediaCreativeDraft(LocalMediaKind.TRANSCRIBE, "随便", "绫华", ""))
    }
}
