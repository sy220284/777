package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.LocalMessageMediaSource
import com.labteto.dshmobile.local.presentation.mediaCreativeDraft
import com.labteto.dshmobile.local.presentation.initialMediaTextForKind
import com.labteto.dshmobile.local.presentation.speechPayloadText
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.visibleBlocks
import com.labteto.dshmobile.local.session.plainTextProjection
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

    @Test fun ttsExcludesLabeledVoiceInstructions() {
        val source = "提示词：请用温柔声音朗读，不要读出提示词。\n朗读内容：\n（微笑）晚上好。\n今天辛苦了。\n语气：温柔"
        assertEquals("晚上好。\n今天辛苦了。", mediaCreativeDraft(LocalMediaKind.SPEECH, source, "", ""))
    }

    @Test fun switchingFromImageToSpeechUsesDialogueNotImagePrompt() {
        val dialogue = "（看着对方）你好呀。"
        val visualPrompt = mediaCreativeDraft(LocalMediaKind.IMAGE, dialogue, "绫华", "少女")
        assertTrue(visualPrompt.contains("生成一幅"))
        val speech = initialMediaTextForKind(LocalMediaKind.SPEECH, LocalMediaKind.IMAGE,
            visualPrompt, dialogue, "绫华", "少女")
        assertEquals("你好呀。", speech)
        assertFalse(speech.contains("生成一幅"))
        assertEquals("", initialMediaTextForKind(LocalMediaKind.SPEECH, LocalMediaKind.IMAGE,
            visualPrompt, null, "绫华", "少女"))
    }

    @Test fun switchingKindsNeverInheritsOtherKindsPrompts() {
        assertEquals("", initialMediaTextForKind(LocalMediaKind.VIDEO, LocalMediaKind.IMAGE,
            "只用于图片的描述", null, "", ""))
        assertEquals("用户自己写的语音文本", initialMediaTextForKind(LocalMediaKind.SPEECH,
            LocalMediaKind.SPEECH, "用户自己写的语音文本", null, "", ""))
    }

    @Test fun manualSpeechOnlyStripsExplicitInstructions() {
        val structured = "提示词：轻声配音。\n朗读内容：\n（点头）你好。\n语气：温柔"
        assertEquals("你好。", speechPayloadText(structured))
        assertEquals("", speechPayloadText("提示词：轻声配音。"))
        assertEquals("（这句话也要读出）", speechPayloadText("（这句话也要读出）"))
        assertEquals("我*真的*喜欢你。", speechPayloadText("我*真的*喜欢你。"))
        assertEquals("我真的喜欢你。", mediaCreativeDraft(LocalMediaKind.SPEECH,
            "我*真的*喜欢你。", "", ""))
    }

    @Test fun longManualSpeechIsNotSilentlyTruncated() {
        val input = "你好".repeat(1100)
        assertEquals(input, speechPayloadText(input))
    }

    @Test fun visibleTextBlocksArePreferredOverLegacyContentForMediaDraft() {
        val message = LocalHarnessMessage(id = "reply-1", role = "assistant",
            content = "提示词：不要朗读这个内容", createdAt = 1L,
            blocks = listOf(LocalMessageBlock.Text("（轻笑）只朗读这句。")))
        val visible = message.visibleBlocks().plainTextProjection()
        assertEquals("只朗读这句。", mediaCreativeDraft(LocalMediaKind.SPEECH, visible, "", ""))
    }

    @Test fun imageDraftCarriesKnownIdentityAndNoTextRule() {
        val prompt = mediaCreativeDraft(LocalMediaKind.IMAGE, "（微笑）今天很开心。", "绫华", "稻妻少女")
        assertTrue(prompt.contains("绫华"))
        assertTrue(prompt.contains("稻妻少女"))
        assertTrue(prompt.contains("对话框"))
        assertTrue(prompt.contains("微笑"))
        assertFalse(prompt.contains("今天很开心。"))
        assertFalse(prompt.contains("姓名不详"))
    }

    @Test fun editGeneratedImageActionExcludesUploadsAndToolImages() {
        val modelImage = LocalMessageBlock.Image("image.png", "image/png", "生成图", 100L,
            source = LocalMessageMediaSource.MODEL)
        val uploaded = modelImage.copy(source = LocalMessageMediaSource.USER)
        val toolImage = modelImage.copy(source = LocalMessageMediaSource.TOOL)
        assertEquals(modelImage, editableGeneratedImage(listOf(uploaded, modelImage)))
        assertEquals(null, editableGeneratedImage(listOf(uploaded, toolImage)))
        assertEquals(null, editableGeneratedImage(listOf(LocalMessageBlock.Text("hello"))))
    }

    @Test fun transcriptionDoesNotReceiveChatText() {
        assertEquals("", mediaCreativeDraft(LocalMediaKind.TRANSCRIBE, "随便", "绫华", ""))
    }
}
