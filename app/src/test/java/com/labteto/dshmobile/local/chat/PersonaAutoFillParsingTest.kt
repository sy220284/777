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
              "identity": "星核猎手成员",
              "coreMotivations": "活下去；守护珍视的人",
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
        assertEquals(listOf("活下去", "守护珍视的人"), draft.coreMotivations)
        assertEquals(1, draft.loreEntries.size)
        assertEquals(listOf("星核猎手", "萨姆"), draft.loreEntries.single().keywords)
        assertEquals(80, draft.loreEntries.single().priority)
        assertTrue(draft.loreEntries.single().alwaysOn)
    }

    @Test
    fun extractsFirstBalancedObjectWithoutBeingConfusedByBracesInStrings() {
        val raw = """
            前置说明
            {
              "name": "流萤",
              "background": "她记得一句话：{别怕，我在。}",
              "exampleDialogues": "第一句\n第二句",
            }
            {"name":"不应读取这个对象"}
        """.trimIndent()

        val draft = parsePersonaDraft(json, raw)

        assertEquals("流萤", draft.name)
        assertEquals("她记得一句话：{别怕，我在。}", draft.background)
        assertEquals(listOf("第一句", "第二句"), draft.exampleDialogues)
    }

    @Test
    fun rejectsTruncatedObjectSoCallerCanTriggerRepair() {
        val result = runCatching {
            parsePersonaDraft(
                json,
                """{"name":"流萤","identity":"星核猎手成员"""",
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("unterminated"))
    }
}
