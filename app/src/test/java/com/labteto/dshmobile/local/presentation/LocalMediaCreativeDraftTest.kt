package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.LocalMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaCreativeDraftTest {
    private val explanation = """
        哈喽！我在呢 😊
        我是本机工作智能体，可以帮你处理这台机器上的实际工作，比如：
        • 读写、整理文件，检索代码或文档
        • 运行命令、排查报错、验证改动效果
        • 分析仓库代码、定位问题根因并修复
        • 处理数据、写脚本、做小工具
        你想做点什么？直接说目标就行。
    """.trimIndent()

    @Test fun explanatoryAssistantReplyIsNeverSentAsAnImageOrVideoPrompt() {
        assertEquals("", mediaCreativeDraft(LocalMediaKind.IMAGE, explanation, "", ""))
        assertEquals("", mediaCreativeDraft(LocalMediaKind.VIDEO, explanation, "", ""))
    }

    @Test fun actorStageDirectionBecomesAVisualPromptWithoutDialogue() {
        val reply = "哈喽！我在呢（笑着挥手，站在窗前，阳光洒在桌面上）我可以帮你整理文件。"
        val draft = mediaCreativeDraft(LocalMediaKind.IMAGE, reply, "", "")
        assertTrue(draft.contains("笑着挥手，站在窗前，阳光洒在桌面上"))
        assertTrue(draft.contains("画面内不得出现文字"))
        assertFalse(draft.contains("我可以帮你整理文件"))
        assertFalse(draft.contains("哈喽"))
    }

    @Test fun inlineStageDirectionDoesNotImportSurroundingCapabilityExplanation() {
        val reply = "你好！（女孩站在雨中握着红伞）我可以整理文件、运行命令。"
        val draft = mediaCreativeDraft(LocalMediaKind.IMAGE, reply, "", "")
        assertTrue(draft.contains("女孩站在雨中握着红伞"))
        assertFalse(draft.contains("运行命令"))
        assertFalse(draft.contains("我可以整理文件"))
    }

    @Test fun stageAndNarrativeAreBothRetained() {
        val reply = "女孩坐在窗边，夕阳映在墙上。（她微笑着挥手）你好。"
        val draft = mediaCreativeDraft(LocalMediaKind.IMAGE, reply, "", "")
        assertTrue(draft.contains("女孩坐在窗边"))
        assertTrue(draft.contains("她微笑着挥手"))
        assertFalse(draft.contains("你好"))
    }

    @Test fun directNarrativeSceneStillPrefillsAnImage() {
        val reply = "黄昏时分，女孩站在山顶，举起灯笼，远处夕阳映红了云层。\n我还能帮你分析代码。"
        val draft = mediaCreativeDraft(LocalMediaKind.IMAGE, reply, "阿青", "她穿着蓝色长裙")
        assertTrue(draft.contains("女孩站在山顶"))
        assertTrue(draft.contains("她穿着蓝色长裙"))
        assertTrue(draft.contains("画面人物：阿青"))
        assertFalse(draft.contains("分析代码"))
    }

    @Test fun nonVisualPersonaBiographyDoesNotBecomeAnImageDescription() {
        val reply = "（她站在书桌前微笑）你好。"
        val draft = mediaCreativeDraft(
            LocalMediaKind.IMAGE, reply, "助手", "我能运行命令，查阅文件和定位问题。"
        )
        assertFalse(draft.contains("运行命令"))
        assertTrue(draft.contains("站在书桌前微笑"))
    }

    @Test fun speechAndExplicitManualPromptsRemainUnchanged() {
        assertEquals(
            "你好。我在这里。",
            mediaCreativeDraft(LocalMediaKind.SPEECH, "你好。（微笑）我在这里。", "", "")
        )
        val manual = "写着中文标题「春日」的海报，文字要清晰。"
        assertEquals(
            manual,
            initialMediaTextForKind(LocalMediaKind.IMAGE, LocalMediaKind.IMAGE, manual, explanation, "", "")
        )
    }

    @Test fun switchingFromSpeechToImageDoesNotReinterpretExplanationsAsScenes() {
        assertEquals(
            "",
            initialMediaTextForKind(LocalMediaKind.IMAGE, LocalMediaKind.SPEECH,
                "你好，我在呢。", explanation, "", "")
        )
    }
}
