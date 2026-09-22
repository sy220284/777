package com.labteto.dshmobile.local.memory

import com.labteto.dshmobile.local.LocalConversationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPolicyTest {
    private val policy = MemoryPolicy()
    private val conflicts = MemoryConflictResolver()

    @Test
    fun explicitRememberCreatesGlobalMemory() {
        val candidate = policy.extractExplicitUserDirective(
            text = "记住以后代码修改完成后要复查",
            mode = LocalConversationMode.INDEPENDENT,
            projectId = null,
        )

        requireNotNull(candidate)
        assertEquals(MemoryScope.GLOBAL, candidate.scope)
        assertEquals(MemoryKind.RULE, candidate.kind)
        assertTrue(candidate.content.contains("代码修改完成后要复查"))
    }

    @Test
    fun projectDirectiveStaysInsideProject() {
        val candidate = policy.extractExplicitUserDirective(
            text = "本项目以后只构建 arm64-v8a",
            mode = LocalConversationMode.PROJECT,
            projectId = "777",
        )

        requireNotNull(candidate)
        assertEquals(MemoryScope.PROJECT, candidate.scope)
        assertEquals(MemoryKind.DECISION, candidate.kind)
    }

    @Test
    fun durableRuleDefaultsToProjectScopeInsideProjectConversation() {
        val candidate = policy.extractExplicitUserDirective(
            text = "以后只发布 arm64-v8a",
            mode = LocalConversationMode.PROJECT,
            projectId = "777",
        )

        requireNotNull(candidate)
        assertEquals(MemoryScope.PROJECT, candidate.scope)
    }

    @Test
    fun safeRuleMayMentionSecretConceptWithoutContainingSecretValue() {
        val candidate = policy.extractExplicitUserDirective(
            text = "以后不要把密钥写入日志",
            mode = LocalConversationMode.INDEPENDENT,
            projectId = null,
        )

        requireNotNull(candidate)
        assertEquals(MemoryScope.GLOBAL, candidate.scope)
    }

    @Test
    fun projectDirectiveIsIgnoredInIndependentConversation() {
        val candidate = policy.extractExplicitUserDirective(
            text = "本项目以后只构建 arm64-v8a",
            mode = LocalConversationMode.INDEPENDENT,
            projectId = null,
        )

        assertNull(candidate)
    }

    @Test
    fun sensitiveDirectiveIsNeverCaptured() {
        val candidate = policy.extractExplicitUserDirective(
            text = "记住我的 API key 是 sk-1234567890abcdefghij",
            mode = LocalConversationMode.INDEPENDENT,
            projectId = null,
        )

        assertNull(candidate)
    }

    @Test
    fun questionsAreNotCapturedAsRules() {
        val candidate = policy.extractExplicitUserDirective(
            text = "以后都只构建 arm64-v8a 可以吗？",
            mode = LocalConversationMode.PROJECT,
            projectId = "777",
        )

        assertNull(candidate)
    }

    @Test
    fun highlySimilarMemorySupersedesOlderRecord() {
        val old = memory("以后代码修改完成后必须复查")
        val candidate = MemoryCandidate(
            content = "以后代码修改完成后必须进行复查",
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.RULE,
            importance = 90,
        )

        val replacement = conflicts.findReplacement(candidate, listOf(old))

        assertSame(old, replacement)
    }

    @Test
    fun unrelatedMemoryIsNotSuperseded() {
        val old = memory("以后代码修改完成后必须复查")
        val candidate = MemoryCandidate(
            content = "以后回答统一使用简体中文",
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.RULE,
            importance = 90,
        )

        assertNull(conflicts.findReplacement(candidate, listOf(old)))
    }

    private fun memory(content: String) = MemoryRecord(
        id = "old",
        scope = MemoryScope.GLOBAL,
        kind = MemoryKind.RULE,
        content = content,
        importance = 90,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
