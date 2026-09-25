package com.labteto.dshmobile.local.memory

import com.labteto.dshmobile.local.LocalConversationMode
import javax.inject.Inject
import javax.inject.Singleton

data class MemoryCandidate(
    val content: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val importance: Int,
    val subjectKey: String? = null,
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

    /**
     * Capture only high-confidence relationship facts from Chat mode.
     *
     * This intentionally ignores inferred intent, attachment labels and transient emotion. The
     * model may reason about those in the current turn, but they must not silently become durable
     * memory.
     */
    fun extractChatRelationshipFact(
        text: String,
        subjectLabel: String? = null,
    ): MemoryCandidate? {
        val raw = text.trim()
        val clean = REMEMBER.matchEntire(raw)?.groupValues?.getOrNull(1)?.trim() ?: raw
        if (clean.length !in MIN_CHARS..MAX_SOURCE_CHARS) return null
        if (clean.endsWith("?") || clean.endsWith("？")) return null
        if (containsSensitiveData(clean)) return null

        NAMED_RELATIONSHIP_STATE.matchEntire(clean)?.let { match ->
            val person = match.groupValues[1].trim()
            val state = normalizeRelationshipState(match.groupValues[2])
            if (person.isNotBlank() && state.isNotBlank()) {
                return MemoryCandidate(
                    content = "关系状态：我和$person｜$state",
                    scope = MemoryScope.GLOBAL,
                    kind = MemoryKind.RELATIONSHIP_STATE,
                    importance = 86,
                )
            }
        }

        PRONOUN_RELATIONSHIP_STATE.matchEntire(clean)?.let { match ->
            val state = normalizeRelationshipState(match.groupValues[1])
            if (state.isNotBlank()) {
                val subject = subjectLabel?.trim()?.take(24)?.takeIf(String::isNotBlank)
                return if (subject != null) {
                    MemoryCandidate(
                        content = "关系状态：我和$subject｜$state",
                        scope = MemoryScope.GLOBAL,
                        kind = MemoryKind.RELATIONSHIP_STATE,
                        importance = 86,
                    )
                } else {
                    MemoryCandidate(
                        content = "当前对话关系状态：我们｜$state",
                        scope = MemoryScope.LINEAGE,
                        kind = MemoryKind.RELATIONSHIP_STATE,
                        importance = 82,
                    )
                }
            }
        }

        OBJECT_IDENTITY.matchEntire(clean)?.let { match ->
            val person = match.groupValues[1].trim()
            val relation = match.groupValues[2].trim().lowercase()
            if (person.isNotBlank()) {
                return MemoryCandidate(
                    content = "关系对象：$person｜$relation",
                    scope = MemoryScope.GLOBAL,
                    kind = MemoryKind.RELATIONSHIP_FACT,
                    importance = 88,
                )
            }
        }

        USER_RELATIONSHIP_PREFERENCE.matchEntire(clean)?.let {
            return MemoryCandidate(
                content = "用户关系偏好：${clean.take(MAX_MEMORY_CHARS)}",
                scope = MemoryScope.GLOBAL,
                kind = MemoryKind.RELATIONSHIP_PREFERENCE,
                importance = 84,
            )
        }

        PARTNER_STABLE_PATTERN.matchEntire(clean)?.let {
            return MemoryCandidate(
                content = "关系对象稳定信息：${clean.take(MAX_MEMORY_CHARS)}",
                scope = MemoryScope.LINEAGE,
                kind = MemoryKind.RELATIONSHIP_FACT,
                importance = 78,
            )
        }

        return null
    }

    private fun normalizeRelationshipState(raw: String): String =
        raw.trim()
            .removeSuffix("了")
            .removeSuffix("中")
            .replace("确认关系", "确定关系")

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

        val NAMED_RELATIONSHIP_STATE = Regex(
            """^(?:我和|我跟|我与)(.{1,24}?)(?:已经|现在|刚刚|刚|正式)?(在一起了?|分手了?|复合了?|确认关系了?|确定关系了?|暧昧中?|异地中?|订婚了?|结婚了?|离婚了?|同居了?|冷战中?)$""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val PRONOUN_RELATIONSHIP_STATE = Regex(
            """^我们(?:已经|现在|刚刚|刚|正式)?(在一起了?|分手了?|复合了?|确认关系了?|确定关系了?|暧昧中?|异地中?|订婚了?|结婚了?|离婚了?|同居了?|冷战中?)$""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val OBJECT_IDENTITY = Regex(
            """^(.{1,24}?)(?:是)?我的(女朋友|男朋友|对象|老婆|老公|前任|暧昧对象|喜欢的人|crush)$""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val USER_RELATIONSHIP_PREFERENCE = Regex(
            """^我在(?:恋爱|感情|关系)里(?:一直|比较|很|特别)?(?:喜欢|不喜欢|讨厌|介意|希望|不能接受|接受不了).{2,160}$""",
        )
        val PARTNER_STABLE_PATTERN = Regex(
            """^(?:她|他|对方|对象|女朋友|男朋友|老婆|老公|前任|暧昧对象|喜欢的人)(?:一直|平时|通常|明确说过|明确表示|习惯)(?:不喜欢|喜欢|讨厌|介意|在意).{2,160}$""",
        )
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
