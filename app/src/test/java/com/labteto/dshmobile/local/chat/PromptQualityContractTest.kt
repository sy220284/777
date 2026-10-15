package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.workSystemPrompt
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptQualityContractTest {
    @Test
    fun replySuggestionsUseAnIndependentSystemPrompt() {
        val system = chatReplySuggestionModelMessages("生成接话建议")
            .first()["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("接话建议"))
        assertTrue(system.contains("JSON"))
        assertFalse(system.contains("后台状态整理器"))
        assertFalse(system.contains("diaryDelta"))
    }

    @Test
    fun groupSilenceRuleOnlyAllowsEligibleSpeakersToSkip() {
        val token = "__GROUP_SILENT__"
        val required = groupChatSpeechInstruction(false, token)
        val optional = groupChatSpeechInstruction(true, token)
        assertTrue(required.contains("需要你接话"))
        assertTrue(required.contains("不要输出 " + token))
        assertTrue(optional.contains("确实没有时"))
        assertTrue(optional.contains(token))
    }

    @Test
    fun suggestionStylesAreLabelsRatherThanForcedBuckets() {
        val prompt = ChatInteractionPromptBuilder().suggestionsPrompt(
            PersonaProfile(name = "阿青"), ChatCharacterState(), "今天累吗？", "有一点，你呢？",
        )
        assertTrue(prompt.contains("给1至4条"))
        assertTrue(prompt.contains("不强制覆盖所有风格"))
        assertFalse(prompt.contains("至少1条 bold=true"))
    }

    @Test
    fun workRulesDoNotEncodeToolSpecificWorkflows() {
        val system = workSystemPrompt("/workspace", false)
        assertTrue(system.contains("工作原则"))
        assertTrue(system.contains("权限"))
        assertFalse(system.contains("update_plan"))
    }

    @Test
    fun diaryKeepsEvidenceAndDisclosureBounds() {
        assertTrue(CHAT_DIARY_SYSTEM_INSTRUCTION.contains("subjectiveEvidence"))
        assertTrue(CHAT_DIARY_SYSTEM_INSTRUCTION.contains("PRIVATE"))
        assertTrue(CHAT_DIARY_SYSTEM_INSTRUCTION.contains("不套用统一情绪"))
    }
}
