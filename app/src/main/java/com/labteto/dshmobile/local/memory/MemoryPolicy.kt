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
                !GLOBAL_HINT.containsMatchIn(body)
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
        if (SECRET_AFTER_LABEL.containsMatchIn(text)) return true
        if (BEARER.containsMatchIn(text)) return true
        if (PRIVATE_KEY.containsMatchIn(text)) return true
        if (LONG_SECRET.containsMatchIn(text)) return true
        if (KNOWN_CREDENTIAL.containsMatchIn(text)) return true
        if (JWT.containsMatchIn(text)) return true
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
        val GLOBAL_HINT = Regex("""全局|所有项目|任何项目|所有对话|任何对话|每个项目""")
        val RULE_HINT = Regex("""以后|后续|禁止|必须|只能|只用|一律|统一|默认|不要|不得|需要|要求""")
        val DECISION_HINT = Regex("""采用|确定|改为|切换为|发布|构建|架构|方案""")
        val SECRET_AFTER_LABEL = Regex(
            """(?i)(?:验证码|密码|口令|密钥|api\s*key|apikey|access\s*token|refresh\s*token|token|password)\s+(?:是\s*)?[a-z0-9._~+/=-]{6,}""",
        )
        val BEARER = Regex("""(?i)bearer\s+[a-z0-9._~+/=-]{16,}""")
        val PRIVATE_KEY = Regex("""(?i)-----BEGIN [A-Z ]*PRIVATE KEY-----""")
        val LONG_SECRET = Regex("""(?i)(?:sk-|key[-_:]?|token[-_:]?|secret[-_:]?)[a-z0-9._~+/=-]{12,}""")
        val KNOWN_CREDENTIAL = Regex(
            """(?i)\b(?:gh[pousr]_[a-z0-9]{20,}|github_pat_[a-z0-9_]{20,}|(?:AKIA|ASIA)[A-Z0-9]{16}|AIza[a-z0-9_-]{35}|xox[baprs]-[a-z0-9-]{10,})\b""",
        )
        val JWT = Regex(
            """(?i)\beyJ[a-z0-9_-]{8,}\.[a-z0-9_-]{8,}\.[a-z0-9_-]{8,}\b""",
        )
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)(?:密码|口令|验证码|密钥|私钥|助记词|password|passwd|api\s*key|apikey|access\s*token|refresh\s*token)\s*(?:是|为|[:=])\s*\S{4,}""",
        )
    }
}
