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
    fun rememberDefaultsToProjectScopeInsideProjectConversation() {
        val candidate = policy.extractExplicitUserDirective(
            text = "记住以后只发布 arm64-v8a",
            mode = LocalConversationMode.PROJECT,
            projectId = "777",
        )

        requireNotNull(candidate)
        assertEquals(MemoryScope.PROJECT, candidate.scope)
    }

    @Test
    fun explicitGlobalHintEscapesProjectScope() {
        val candidate = policy.extractExplicitUserDirective(
            text = "记住所有项目以后都要先复查再提交",
            mode = LocalConversationMode.PROJECT,
            projectId = "777",
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
    fun knownCredentialFormatsAreRejected() {
        assertTrue(policy.containsSensitiveData("ghp_abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue(policy.containsSensitiveData("AKIA1234567890ABCDEF"))
        assertTrue(
            policy.containsSensitiveData(
                "eyJabcdefghijk.eyJabcdefghijk.abcdefghijklmnop",
            ),
        )
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
    fun namedRelationshipStateIsDurableGlobalState() {
        val candidate = policy.extractChatRelationshipFact("我和林晚刚在一起了")

        requireNotNull(candidate)
        assertEquals(MemoryScope.GLOBAL, candidate.scope)
        assertEquals(MemoryKind.RELATIONSHIP_STATE, candidate.kind)
        assertEquals("关系状态：我和林晚｜在一起", candidate.content)
    }

    @Test
    fun unnamedRelationshipStateStaysInsideConversationLineage() {
        val candidate = policy.extractChatRelationshipFact("我们刚确定关系了")

        requireNotNull(candidate)
        assertEquals(MemoryScope.LINEAGE, candidate.scope)
        assertEquals(MemoryKind.RELATIONSHIP_STATE, candidate.kind)
        assertEquals("当前对话关系状态：我们｜确定关系", candidate.content)
    }

    @Test
    fun pronounRelationshipStateBindsToKnownPersonaAcrossChats() {
        val candidate = policy.extractChatRelationshipFact(
            text = "我们刚在一起了",
            subjectLabel = "林晚",
        )

        requireNotNull(candidate)
        assertEquals(MemoryScope.GLOBAL, candidate.scope)
        assertEquals(MemoryKind.RELATIONSHIP_STATE, candidate.kind)
        assertEquals("关系状态：我和林晚｜在一起", candidate.content)
    }

    @Test
    fun explicitRelationshipObjectIsRememberedAsFact() {
        val candidate = policy.extractChatRelationshipFact("林晚是我的女朋友")
        val explicitRemember = policy.extractChatRelationshipFact("记住林晚是我的女朋友")

        requireNotNull(candidate)
        requireNotNull(explicitRemember)
        assertEquals(MemoryScope.GLOBAL, candidate.scope)
        assertEquals(MemoryKind.RELATIONSHIP_FACT, candidate.kind)
        assertEquals("关系对象：林晚｜女朋友", candidate.content)
        assertEquals(candidate.content, explicitRemember.content)
        assertEquals(MemoryKind.RELATIONSHIP_FACT, explicitRemember.kind)
    }

    @Test
    fun stablePartnerPreferenceMayBeRememberedButInferenceMayNot() {
        val stable = policy.extractChatRelationshipFact("她平时不喜欢别人连着问她问题")
        val inference = policy.extractChatRelationshipFact("她可能是回避型依恋")
        val unrelated = policy.extractChatRelationshipFact("这部电影平时喜欢用冷色调")

        requireNotNull(stable)
        assertEquals(MemoryScope.LINEAGE, stable.scope)
        assertNull(inference)
        assertNull(unrelated)
    }

    @Test
    fun relationshipQuestionsAreNeverSavedAsFacts() {
        assertNull(policy.extractChatRelationshipFact("我们是不是在一起了？"))
        assertNull(policy.extractChatRelationshipFact("她喜欢我吗？"))
    }

    @Test
    fun relationshipStateSupersedesSamePersonWithoutDependingOnTextSimilarity() {
        val old = MemoryRecord(
            id = "old-state",
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            content = "关系状态：我和林晚｜在一起",
            importance = 86,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val candidate = MemoryCandidate(
            content = "关系状态：我和林晚｜确定关系",
            scope = MemoryScope.GLOBAL,
            kind = MemoryKind.RELATIONSHIP_STATE,
            importance = 86,
        )

        assertSame(old, conflicts.findReplacement(candidate, listOf(old)))
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
