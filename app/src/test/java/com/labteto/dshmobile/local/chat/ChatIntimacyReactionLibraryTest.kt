package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIntimacyReactionLibraryTest {
    private val library = ChatIntimacyReactionLibrary
    private val mode = ChatIntimacyMode()

    @Test
    fun coversEighteenScenesAndNineVocalFamilies() {
        assertEquals(18, library.sceneCount)
        assertEquals(9, library.vocalFamilyCount)
    }

    @Test
    fun choosesSpecificContactInsteadOfGenericHugAndKiss() {
        val previous = ChatCharacterState()
        assertEquals("sudden-hug", library.select("她突然抱住我", previous).sceneId)
        assertEquals("forehead-kiss", library.select("她亲吻我的额头", previous).sceneId)
        assertEquals("cheek-kiss", library.select("她轻吻了我的脸颊", previous).sceneId)
        assertEquals("hair-touch", library.select("她轻抚我的头发", previous).sceneId)
        assertEquals("hand-touch", library.select("她摩挲着我的手背", previous).sceneId)
    }

    @Test
    fun choosesConversationEvidenceForContinuation() {
        val previous = ChatCharacterState(
            interactionIntent = ChatInteractionIntent.INTIMATE.name,
            interactionIntentStrength = 100,
            interactionIntensity = 5,
            recentActionTags = listOf("拥抱"),
            recentPoseTags = listOf("并肩"),
            recentVerbalTags = listOf("反撩接梗"),
        )
        val chosen = library.select("继续刚才的", previous)
        assertEquals("gentle-hug", chosen.sceneId)
        assertTrue(chosen.guidance.contains("不重演上轮的开始动作"))
        assertTrue(chosen.guidance.contains("近期已用：拥抱"))
        assertTrue(chosen.guidance.contains("并肩"))
    }

    @Test
    fun unspecifiedActionDoesNotInventPhysicalContact() {
        val chosen = library.select("这次让我们慢慢来", ChatCharacterState())
        assertEquals("unspecified", chosen.sceneId)
        assertTrue(chosen.guidance.contains("不凭空补出拥抱、亲吻"))
    }

    @Test
    fun personaMoodAndSettingActuallyAffectGuidance() {
        val persona = PersonaProfile(name = "阿青", portrait = "性格慢热，外冷内热")
        val state = ChatCharacterState(mood = "紧张")
        val context = ChatContextState(scene = ChatSceneState(location = "厨房", sceneTime = "晚上"))
        val guidance = library.select("她抱住我", state, context, persona).guidance
        assertTrue(guidance.contains("人物表达：可先停顿"))
        assertTrue(guidance.contains("当前已记录情绪：紧张"))
        assertTrue(guidance.contains("留意手边物件"))
        assertTrue(guidance.contains("微动作："))
        assertTrue(guidance.contains("身体："))
        assertTrue(guidance.contains("表情/目光："))
        assertTrue(guidance.contains("语言："))
        assertTrue(guidance.contains("自然声音："))
    }

    @Test
    fun noPersonaTraitsDoesNotInventAStyleOrFeeling() {
        val guidance = library.select("她拥抱了我", ChatCharacterState()).guidance
        assertFalse(guidance.contains("人物表达："))
        assertFalse(guidance.contains("当前已记录情绪："))
        assertTrue(guidance.contains("人物意愿以明确表达为准"))
    }

    @Test
    fun fullReactionGuidanceOnlyAppearsInFiveOfFiveMode() {
        val plain = mode.prompt("今天有点累", ChatCharacterState(), ChatContextState(),
            PersonaProfile(name = "阿青", portrait = "害羞"))
        assertEquals("", plain)
        val flirting = mode.prompt("今晚撩我一下", ChatCharacterState())
        assertFalse(flirting.contains("【本轮反应素材"))
        val intimate = mode.prompt("她亲吻我的额头", ChatCharacterState())
        assertTrue(intimate.contains("【本轮反应素材"))
        assertTrue(intimate.contains("情境=forehead-kiss"))
        assertTrue(intimate.contains("【身体反应强度】当前=5/5"))
    }

    @Test
    fun runtimeProjectionPassesStoryScopedPersonaAndScene() {
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val persona = PersonaProfile(name = "阿青", portrait = "慢热而温柔")
        val context = ChatContextState(scene = ChatSceneState(location = "沙发", sceneTime = "夜晚"))
        val projection = projector.project(persona, ChatCharacterState(mood = "开心"),
            context, "她轻轻抱住了我", null)
        assertTrue(projection.dynamicPrompt.contains("【本轮反应素材"))
        assertTrue(projection.dynamicPrompt.contains("注意坐姿、肩膀与支撑点"))
        assertTrue(projection.dynamicPrompt.contains("人物表达：可先停顿"))
        assertFalse(projection.stablePrompt.contains("【本轮反应素材"))
    }

    @Test
    fun plainStoryProjectionRemainsUnaffected() {
        val projector = CharacterRuntimeProjector(ChatRelationshipEngine(), CharacterLoreEngine())
        val projection = projector.project(PersonaProfile(name = "阿青"),
            ChatCharacterState(), ChatContextState(scene = ChatSceneState(location = "厨房")),
            "工作完成了吗", null)
        assertFalse(projection.dynamicPrompt.contains("【本轮反应素材"))
        assertFalse(projection.dynamicPrompt.contains("【身体反应强度】"))
    }
}
