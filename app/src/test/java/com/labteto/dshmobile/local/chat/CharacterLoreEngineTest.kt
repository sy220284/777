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
