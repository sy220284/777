package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRowModelTest {
    @Test
    fun `tool rows never expose arguments paths queries or tool names`() {
        val samples = listOf(
            Triple("web_search", """{"queries":["secret query","another query"]}""", null),
            Triple("read", """{"file_path":"D:\\LabTeto\\deepseek-mobile\\app\\build.gradle.kts"}""", "D:\\LabTeto\\deepseek-mobile"),
            Triple("edit", """{"path":"/home/me/project/src/main.kt"}""", "/home/me/project"),
            Triple("bash", """{"description":"Push tag","command":"git push --tags"}""", null),
            Triple("some_new_tool", """{"whatever":"private"}""", null),
        )

        samples.forEach { (name, args, cwd) ->
            val row = toolRowModel(name, args, cwd)
            assertNull(row.summary)
            assertNull(row.filePath)
        }
    }

    @Test
    fun `tool classification still drives generic icons and categories`() {
        assertEquals(ToolRowVariant.Search, classifyTool("web_search"))
        assertEquals(ToolRowVariant.Read, classifyTool("read"))
        assertEquals(ToolRowVariant.Edit, classifyTool("edit"))
        assertEquals(ToolRowVariant.Bash, classifyTool("bash"))
        assertEquals(ToolRowVariant.Other, classifyTool("some_new_tool"))
    }

    @Test
    fun `basename handles both separators`() {
        assertEquals("deepseek-mobile", basename("D:\\LabTeto\\deepseek-mobile"))
        assertEquals("project", basename("/home/me/project/"))
        assertEquals("plain", basename("plain"))
    }
}
