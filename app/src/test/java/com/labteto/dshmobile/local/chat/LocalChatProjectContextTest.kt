package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalConversationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatProjectContextTest {
    @Test fun projectRulesArePartOfStableContextForProjectAndContinuation() {
        var resolved: String? = null
        val lookup: (String?) -> String = { id ->
            resolved = id
            "核对来源后再下结论"
        }
        val project = withChatProjectInstructions("原人物设定", LocalConversationMode.PROJECT, "project-a", lookup)
        assertTrue(project.startsWith("原人物设定"))
        assertTrue(project.contains("核对来源后再下结论"))
        assertEquals("project-a", resolved)
        val continuation = withChatProjectInstructions("原人物设定", LocalConversationMode.CONTINUATION, "project-a", lookup)
        assertTrue(continuation.contains("核对来源后再下结论"))
    }

    @Test fun independentOrUnboundConversationsCannotReadProjectInstructions() {
        var lookups = 0
        val lookup: (String?) -> String = { _ -> lookups++; "不应泄漏" }
        val independent = withChatProjectInstructions("人物设定", LocalConversationMode.INDEPENDENT, "project-a", lookup)
        val unbound = withChatProjectInstructions("人物设定", LocalConversationMode.PROJECT, null, lookup)
        assertEquals("人物设定", independent)
        assertEquals("人物设定", unbound)
        assertEquals(0, lookups)
        assertFalse(independent.contains("不应泄漏"))
    }

    @Test fun missingProjectInstructionsLeaveThePromptAndCachePrefixUnchanged() {
        val result = withChatProjectInstructions("稳定规则", LocalConversationMode.PROJECT, "retired", { "" })
        assertEquals("稳定规则", result)
    }
}
