package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaAutoFillParsingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun parsesFencedLooseShapesAndTrailingCommas() {
        val raw = """
            这里是整理结果：
            ```json
            {
              "name": "流萤",
              "portrait": "星核猎手成员",
              "coreValues": "活下去；守护珍视的人",
              "attentionKeywords": "匹诺康尼；星核猎手；萨姆",
              "franchise": "崩坏：星穹铁道",
              "timelinePosition": "匹诺康尼主线初期",
              "knowledgeBoundary": ["未知的后续剧情不能直接引用"],
              "hardConstraints": ["避免机械式解释", "保持角色视角"],
              "loreEntries": {
                "title": "萨姆",
                "content": "装甲形态相关设定",
                "keywords": "星核猎手；萨姆",
                "priority": "80",
                "alwaysOn": "true",
                "spoilerLevel": "0"
              },
            }
            ```
            完成。
        """.trimIndent()

        val draft = parsePersonaDraft(json, raw)

        assertEquals("流萤", draft.name)
        assertEquals(listOf("活下去", "守护珍视的人"), draft.coreValues)
        assertEquals(listOf("匹诺康尼", "星核猎手", "萨姆"), draft.attentionKeywords)
        assertEquals("崩坏：星穹铁道", draft.franchise)
        assertEquals("匹诺康尼主线初期", draft.timelinePosition)
        assertEquals(listOf("未知的后续剧情不能直接引用"), draft.knowledgeBoundary)
        assertEquals(1, draft.loreEntries.size)
        assertEquals(listOf("星核猎手", "萨姆"), draft.loreEntries.single().keywords)
        assertEquals(80, draft.loreEntries.single().priority)
        assertTrue(draft.loreEntries.single().alwaysOn)
    }

    @Test
    fun generatedWorldBookUpdatesAreIncrementalAndKeepExistingSpoilerBoundary() {
        val original = listOf(
            PersonaLoreEntry(
                id = "first", title = "社奉行", content = "原作旧设定",
                keywords = listOf("社奉行"), priority = 85, alwaysOn = true, spoilerLevel = 2,
            ),
            PersonaLoreEntry(
                id = "second", title = "托马", content = "和神里家关系密切",
                keywords = listOf("托马"), priority = 80,
            ),
        )
        val generated = listOf(
            PersonaLoreEntry(
                title = "社奉行", content = "原作更新设定",
                keywords = listOf("神里家"), spoilerLevel = 0,
            ),
            PersonaLoreEntry(
                title = "冰元素", content = "绫华使用冰元素",
                keywords = listOf("冰元素"),
            ),
        )
        val merged = mergeGeneratedLoreEntries(original, generated)
        assertEquals(3, merged.size)
        assertEquals("原作旧设定", merged[0].content)
        assertEquals("first", merged[0].id)
        assertEquals(listOf("社奉行", "神里家"), merged[0].keywords)
        assertTrue(merged[0].alwaysOn)
        assertEquals(85, merged[0].priority)
        assertEquals(2, merged[0].spoilerLevel)
        assertEquals(original[1], merged[1])
        assertTrue(merged[2].id.isNotBlank())
    }

    @Test
    fun extractsFirstBalancedObjectWithoutBeingConfusedByBracesInStrings() {
        val raw = """
            前置说明
            {
              "name": "流萤",
              "lifeContext": "她记得一句话：{别怕，我在。}",
              "voiceSamples": "第一句\n第二句",
            }
            {"name":"不应读取这个对象"}
        """.trimIndent()

        val draft = parsePersonaDraft(json, raw)

        assertEquals("流萤", draft.name)
        assertEquals("她记得一句话：{别怕，我在。}", draft.lifeContext)
        assertEquals(listOf("第一句", "第二句"), draft.voiceSamples)
    }

    @Test
    fun rejectsTruncatedObjectSoCallerCanTriggerRepair() {
        val result = runCatching {
            parsePersonaDraft(
                json,
                """{"name":"流萤","portrait":"星核猎手成员"""",
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("unterminated"))
    }
}
