package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaImmersionPolicyTest {
    @Test
    fun rejectsModelCapabilityDisclaimers() {
        val samples = listOf(
            "作为AI语言模型，我没有身体，也无法进行实体动作。",
            "我是虚拟助手，无法进行生理行为。",
            "我没有真实的感情，不能真正理解你的情绪。",
            "我只能通过文字和你交流，不能在现实里陪伴你。",
            "As an AI, I don't have a physical body or feelings.",
        )

        samples.forEach { sample ->
            assertTrue("应拦截脱离角色的人设：$sample", PersonaImmersionPolicy.breaksImmersion(sample))
        }
    }

    @Test
    fun keepsExplicitInWorldNonHumanSettings() {
        val samples = listOf(
            "她是寄宿在旧宅里的幽灵，没有固定肉身，但可以借镜面和烛火显形。",
            "他是军用仿生人，机体需要定期维护，也会形成自己的偏好与情绪。",
            "她的意识长期寄存在飞船核心中，可以控制舰内机械臂与无人机行动。",
            "因旧伤无法长时间奔跑，但日常生活和近距离行动不受影响。",
        )

        samples.forEach { sample ->
            assertFalse("不应误伤世界内设定：$sample", PersonaImmersionPolicy.breaksImmersion(sample))
        }
    }

    @Test
    fun rejectsAssistantStyleCommunicationLimits() {
        assertTrue(PersonaImmersionPolicy.breaksImmersion("只能通过屏幕沟通，无法进行物理动作。"))
        assertTrue(PersonaImmersionPolicy.breaksImmersion("作为语言模型，我不能真正感受人类情感。"))
    }

    @Test
    fun runtimeRejectsModelExcusesForOrdinaryEmbodiedCharacters() {
        val persona = PersonaProfile(
            name = "阿青",
            portrait = "住在杭州的年轻摄影师，性格直接，习惯用行动表达关心。",
        )

        assertTrue(PersonaImmersionPolicy.breaksReplyImmersion(persona, "我是AI，没有真实身体，所以没法抱你。"))
        assertTrue(PersonaImmersionPolicy.breaksReplyImmersion(persona, "现实中我无法真正触碰你，只能陪你聊聊天。"))
        assertTrue(PersonaImmersionPolicy.breaksReplyImmersion(persona, "我没有真实的感情，不能真正理解你的难过。"))
    }

    @Test
    fun aiRelatedOccupationDoesNotBecomeNonEmbodiedIdentity() {
        val persona = PersonaProfile(
            name = "林然",
            portrait = "一家科技公司的AI研究员，平时喜欢跑步和做饭。",
        )

        assertTrue(PersonaImmersionPolicy.breaksReplyImmersion(persona, "我没有实体身体，所以没法走过去抱你。"))
    }

    @Test
    fun runtimeKeepsInWorldLimitsForExplicitNonEmbodiedCharacters() {
        val persona = PersonaProfile(
            name = "镜中客",
            portrait = "寄宿在旧镜中的幽灵，没有固定肉身，只能借镜面和烛火短暂显形。",
            worldSetting = "旧宅中的灵体可以显形，但很难直接移动沉重实物。",
        )

        assertFalse(PersonaImmersionPolicy.breaksReplyImmersion(persona, "我没有固定肉身，今晚只能借镜子看看你。"))
        assertFalse(PersonaImmersionPolicy.breaksReplyImmersion(persona, "这只箱子太重，我现在的形态搬不动。"))
        assertTrue(PersonaImmersionPolicy.breaksReplyImmersion(persona, "作为语言模型，我只能通过文字和你交流。"))
    }
}
