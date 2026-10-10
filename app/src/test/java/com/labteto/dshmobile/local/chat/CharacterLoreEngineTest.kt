package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterLoreEngineTest {
    private val engine = CharacterLoreEngine()

    @Test
    fun activatesOnlyRelevantLoreAndAlwaysOnEntries() {
        val persona = PersonaProfile(
            name = "神里绫华",
            loreEntries = listOf(
                PersonaLoreEntry(
                    id = "thoma",
                    title = "托马",
                    content = "托马是神里家的重要伙伴。",
                    keywords = listOf("托马"),
                    priority = 80,
                ),
                PersonaLoreEntry(
                    id = "festival",
                    title = "祭典",
                    content = "稻妻会举办各类祭典。",
                    keywords = listOf("祭典"),
                    priority = 90,
                ),
                PersonaLoreEntry(
                    id = "boundary",
                    title = "边界",
                    content = "保持角色知识边界。",
                    alwaysOn = true,
                    priority = 100,
                ),
            ),
        )

        val active = engine.activated(persona, "托马最近怎么样？")

        assertEquals(listOf("boundary", "thoma"), active.map { it.id })
        assertFalse(active.any { it.id == "festival" })
    }

    @Test
    fun unrelatedPriorityDoesNotCauseActivation() {
        val persona = PersonaProfile(
            loreEntries = listOf(
                PersonaLoreEntry(
                    id = "unrelated",
                    title = "完全无关",
                    content = "高优先级也不应凭空出现。",
                    keywords = listOf("雷电将军"),
                    priority = 100,
                ),
            ),
        )

        assertTrue(engine.activated(persona, "今天天气不错").isEmpty())
    }

    @Test
    fun spoilerLevelBlocksFutureLoreByDefault() {
        val persona = PersonaProfile(
            loreEntries = listOf(
                PersonaLoreEntry(
                    id = "safe",
                    title = "基础资料",
                    content = "无剧透资料。",
                    keywords = listOf("身份"),
                    spoilerLevel = 0,
                ),
                PersonaLoreEntry(
                    id = "future",
                    title = "后续秘密",
                    content = "后续剧情信息。",
                    keywords = listOf("身份"),
                    spoilerLevel = 2,
                ),
            ),
        )

        val safe = engine.activated(persona, "身份", maxSpoilerLevel = 0)
        val full = engine.activated(persona, "身份", maxSpoilerLevel = 2)

        assertEquals(listOf("safe"), safe.map { it.id })
        assertEquals(setOf("safe", "future"), full.map { it.id }.toSet())
    }

    @Test
    fun oversizedRelevantLoreDoesNotHideLaterMatchingEntriesOrOverflowBudget() {
        val persona = PersonaProfile(
            loreEntries = listOf(
                PersonaLoreEntry(id = "very-long", title = "长背景",
                    content = "开头。" + "旧资料。".repeat(300) + "目标线索，隐藏在长文本末尾。",
                    keywords = listOf("目标"), priority = 100),
                PersonaLoreEntry(id = "short", title = "当前线索",
                    content = "目标已经回到故乡。", keywords = listOf("目标")),
            ),
        )

        val active = engine.activated(persona, "目标", maxChars = 400)
        assertEquals(listOf("very-long", "short"), active.map { it.id })
        assertTrue(active.sumOf { it.title.length + it.content.length + 24 } <= 400)
        assertTrue(active.first().content.contains("目标线索"))
        assertTrue(active.last().content.contains("回到故乡"))
        assertTrue(persona.loreEntries.first().content.contains("隐藏在长文本末尾"))
    }

    @Test
    fun laterWorldBookEntriesStayHiddenUntilMatchingStageEvenWhenAlwaysOn() {
        val persona = PersonaProfile(loreEntries = listOf(
            PersonaLoreEntry(id = "present", title = "日常", content = "今天仍在书店工作",
                keywords = listOf("书店")),
            PersonaLoreEntry(id = "future", title = "第二幕", content = "第二幕才发现的秘密",
                keywords = listOf("书店"), temporalScope = "act-2", alwaysOn = true),
        ))
        assertEquals(listOf("present"), engine.activated(persona, "书店").map { it.id })
        assertTrue(engine.activated(persona, "书店", storyStage = "act-2").any { it.id == "future" })
        assertFalse(engine.prompt(persona, "书店").contains("第二幕才发现"))
        assertTrue(engine.prompt(persona, "书店", storyStage = "act-2").contains("第二幕才发现"))
    }

    @Test
    fun promptUsesActualLoreTitle() {
        val persona = PersonaProfile(
            loreEntries = listOf(
                PersonaLoreEntry(
                    id = "doctor",
                    title = "医生职业",
                    content = "保持专业边界。",
                    keywords = listOf("医院"),
                ),
            ),
        )

        val prompt = engine.prompt(persona, "今天医院忙吗")

        assertTrue(prompt.contains("【医生职业】"))
        assertFalse(prompt.contains("\${entry.title}"))
    }
}
