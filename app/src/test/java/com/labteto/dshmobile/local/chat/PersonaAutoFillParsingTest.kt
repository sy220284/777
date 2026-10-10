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
    fun parsesV4FactsAndWorldBookFromFencedLooseJson() {
        val raw = """
            这里是整理结果：
            ```json
            {
              "name": "流萤",
              "franchise": "崩坏：星穹铁道",
              "coreIdentity": "星核猎手成员",
              "facts": [
                {
                  "id": "choice",
                  "category": "valuesAndTradeoffs",
                  "content": "珍惜自由与普通生活",
                  "provenance": "INFERRED"
                }
              ],
              "loreEntries": {
                "title": "萨姆",
                "content": "装甲形态相关设定",
                "keywords": "星核猎手；萨姆",
                "priority": "80",
                "alwaysOn": "true",
                "spoilerLevel": "0",
                "temporalScope": "act-2"
              },
            }
            ```
            完成。
        """.trimIndent()

        val draft = parsePersonaDraft(json, raw)
        assertEquals("流萤", draft.name)
        assertEquals("星核猎手成员", draft.coreIdentity)
        assertEquals("崩坏：星穹铁道", draft.franchise)
        assertEquals("珍惜自由与普通生活", draft.facts.single().content)
        assertEquals(CharacterFactProvenance.INFERRED, draft.facts.single().provenance)
        assertEquals(listOf("星核猎手", "萨姆"), draft.loreEntries.single().keywords)
        assertEquals(80, draft.loreEntries.single().priority)
        assertTrue(draft.loreEntries.single().alwaysOn)
        assertEquals("act-2", draft.loreEntries.single().temporalScope)
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
    fun sameTitleWorldBookFactsAtDifferentStoryStagesStayIndependent() {
        val old = PersonaLoreEntry(id = "same", title = "阿宁的身份",
            content = "早期只知道阿宁是普通学徒", temporalScope = "act-1",
            keywords = listOf("阿宁"))
        val later = PersonaLoreEntry(id = "same", title = "阿宁的身份",
            content = "第二幕才确认阿宁的真实身份", temporalScope = "act-2",
            keywords = listOf("阿宁"))
        val merged = mergeGeneratedLoreEntries(listOf(old), listOf(later))
        assertEquals(2, merged.size)
        assertEquals(setOf("act-1", "act-2"), merged.map { it.temporalScope }.toSet())
        assertEquals(2, merged.map { it.id }.distinct().size)
    }

    @Test
    fun extractsFirstBalancedObjectWithoutBeingConfusedByBracesInStrings() {
        val raw = """
            前置说明
            {
              "name": "流萤",
              "coreIdentity": "她记得一句话：{别怕，我在。}",
              "facts": [{"id":"voice","category":"voiceStyle","content":"说话自然"}],
            }
            {"name":"不应读取这个对象"}
        """.trimIndent()

        val draft = parsePersonaDraft(json, raw)
        assertEquals("流萤", draft.name)
        assertEquals("她记得一句话：{别怕，我在。}", draft.coreIdentity)
        assertEquals("说话自然", draft.facts.single().content)
    }

    @Test
    fun rejectsTruncatedObjectSoCallerCanTriggerRepair() {
        val result = runCatching {
            parsePersonaDraft(
                json,
                """{"name":"流萤","coreIdentity":"星核猎手成员"""",
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("unterminated"))
    }
}
