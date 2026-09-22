package com.labteto.dshmobile.local.memory

import com.labteto.dshmobile.local.LocalConversationMode
import javax.inject.Inject
import javax.inject.Singleton

data class MemoryCandidate(
    val content: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val importance: Int,
)

@Singleton
class MemoryPolicy @Inject constructor() {
    fun extractExplicitUserDirective(
        text: String,
        mode: LocalConversationMode,
        projectId: String?,
    ): MemoryCandidate? {
        val clean = text.trim()
        if (clean.length !in MIN_CHARS..MAX_SOURCE_CHARS) return null
        if (clean.endsWith("?") || clean.endsWith("？")) return null
        if (containsSensitiveData(clean)) return null

        PROJECT_RULE.matchEntire(clean)?.let { match ->
            if (projectId == null || mode == LocalConversationMode.INDEPENDENT) return null
            val body = match.groupValues[2].trim().take(MAX_MEMORY_CHARS)
            if (body.length < MIN_CHARS) return null
            return MemoryCandidate(
                content = "本项目：$body",
                scope = MemoryScope.PROJECT,
                kind = classify(body),
                importance = 88,
            )
        }

        REMEMBER.matchEntire(clean)?.let { match ->
            val body = match.groupValues[1].trim().take(MAX_MEMORY_CHARS)
            if (body.length < MIN_CHARS || containsSensitiveData(body)) return null
            val projectScoped = projectId != null &&
                mode != LocalConversationMode.INDEPENDENT &&
                PROJECT_HINT.containsMatchIn(body)
            return MemoryCandidate(
                content = body,
                scope = if (projectScoped) MemoryScope.PROJECT else MemoryScope.GLOBAL,
                kind = classify(body),
                importance = 92,
            )
        }

        DURABLE_RULE.matchEntire(clean)?.let {
            val body = clean.take(MAX_MEMORY_CHARS)
            val scope = if (projectId != null && mode != LocalConversationMode.INDEPENDENT) {
                MemoryScope.PROJECT
            } else {
                MemoryScope.GLOBAL
            }
            return MemoryCandidate(
                content = body,
                scope = scope,
                kind = MemoryKind.RULE,
                importance = 90,
            )
        }

        return null
    }

    fun containsSensitiveData(text: String): Boolean {
        if (SENSITIVE_ASSIGNMENT.containsMatchIn(text)) return true
        if (BEARER.containsMatchIn(text)) return true
        if (LONG_SECRET.containsMatchIn(text)) return true
        return false
    }

    private fun classify(text: String): MemoryKind = when {
        RULE_HINT.containsMatchIn(text) -> MemoryKind.RULE
        DECISION_HINT.containsMatchIn(text) -> MemoryKind.DECISION
        else -> MemoryKind.PREFERENCE
    }

    private companion object {
        const val MIN_CHARS = 4
        const val MAX_SOURCE_CHARS = 1_200
        const val MAX_MEMORY_CHARS = 800

        val REMEMBER = Regex("""^(?:请)?记住[：:，,\s]*(.+)$""", RegexOption.DOT_MATCHES_ALL)
        val DURABLE_RULE = Regex(
            """^(?:以后|后续)(?:都|一律|统一|默认|继续)?[：:，,\s]*.+$""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val PROJECT_RULE = Regex(
            """^(这个项目|本项目|当前项目)(?:中|里|以后|后续)?[：:，,\s]*(.+)$""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val PROJECT_HINT = Regex("""这个项目|本项目|当前项目|项目里|项目中""")
        val RULE_HINT = Regex("""禁止|必须|只能|只用|一律|统一|默认|不要|不得|需要|要求""")
        val DECISION_HINT = Regex("""采用|确定|改为|切换为|发布|构建|架构|方案""")
        val BEARER = Regex("""(?i)bearer\s+[a-z0-9._~+/=-]{16,}""")
        val LONG_SECRET = Regex("""(?i)(?:sk-|key[-_:]?|token[-_:]?|secret[-_:]?)[a-z0-9._~+/=-]{12,}""")
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)(?:密码|口令|验证码|密钥|私钥|助记词|password|passwd|api\s*key|apikey|access\s*token|refresh\s*token)\s*(?:是|为|[:=])\s*\S{4,}""",
        )
    }
}
