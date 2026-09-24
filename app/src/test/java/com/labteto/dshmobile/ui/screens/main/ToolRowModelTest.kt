package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRowModelTest {
    @Test
    fun allArgumentDerivedSummariesStayHidden() {
        val cases = listOf(
            toolRowModel("web_search", """{"queries":["secret query"]}""", null),
            toolRowModel("read", """{"file_path":"/work/private.kt"}""", "/work"),
            toolRowModel("edit", """{"path":"src/main.kt"}""", "/work"),
            toolRowModel("bash", """{"command":"./gradlew test","description":"Run tests"}""", null),
            toolRowModel("process_exec", """{"command":"git status"}""", null),
            toolRowModel("terminal_write", """{"text":"rm -rf build"}""", null),
            toolRowModel("some_new_tool", """{"path":"/hidden","query":"hidden"}""", null),
        )

        cases.forEach { row ->
            assertNull(row.summary)
            assertNull(row.filePath)
        }
    }

    @Test
    fun presenterTitlesCannotReintroduceRawExecutionText() {
        val row = toolRowModel(
            toolName = "pwsh",
            argumentsJson = """{"command":"git push --tags"}""",
            cwd = null,
            viewTitle = "git push --tags",
        )

        assertEquals("Bash", row.title)
        assertNull(row.summary)
    }

    @Test
    fun knownToolsKeepOnlyTheirGenericVariant() {
        assertEquals("Read", toolRowModel("read", "{}", null).title)
        assertEquals("Search", toolRowModel("grep", "{}", null).title)
        assertEquals("Write", toolRowModel("write", "{}", null).title)
        assertEquals("Edit", toolRowModel("edit", "{}", null).title)
        assertEquals("Bash", toolRowModel("bash", "{}", null).title)
        assertEquals("Tool call", toolRowModel("unknown_tool", "{}", null).title)
    }

    @Test
    fun basenameHandlesBothSeparators() {
        assertEquals("deepseek-mobile", basename("D:\\LabTeto\\deepseek-mobile"))
        assertEquals("project", basename("/home/me/project/"))
        assertEquals("plain", basename("plain"))
    }
}
