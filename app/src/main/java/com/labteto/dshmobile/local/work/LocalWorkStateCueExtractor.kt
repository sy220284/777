package com.labteto.dshmobile.local.work



internal enum class LocalWorkCueKind {
    CONSTRAINT,
    DECISION,
    FAILURE,
}

internal fun extractLocalWorkCueSnippet(
    text: String,
    kind: LocalWorkCueKind,
    maxChars: Int = 360,
): String? {
    if (text.isBlank() || maxChars <= 0) return null
    val normalized = text.lowercase()
    val match = cuesFor(kind)
        .asSequence()
        .mapNotNull { cue ->
            normalized.indexOf(cue.lowercase())
                .takeIf { it >= 0 }
                ?.let { index -> index to cue.length }
        }
        .minByOrNull { it.first }
        ?: return null
    if (text.length <= maxChars) return text

    val cueEnd = match.first + match.second
    var start = (match.first - maxChars / 3).coerceAtLeast(0)
    if (cueEnd > start + maxChars) {
        start = (cueEnd - maxChars).coerceAtLeast(0)
    }
    start = start.coerceAtMost((text.length - maxChars).coerceAtLeast(0))
    return text.substring(start, (start + maxChars).coerceAtMost(text.length)).trim()
}

private fun cuesFor(kind: LocalWorkCueKind): List<String> = when (kind) {
    LocalWorkCueKind.CONSTRAINT -> WORK_CONSTRAINT_CUES
    LocalWorkCueKind.DECISION -> WORK_DECISION_CUES
    LocalWorkCueKind.FAILURE -> WORK_FAILURE_CUES
}

private val WORK_CONSTRAINT_CUES = listOf(
    "必须", "禁止", "不能", "不要", "只允许", "仅限", "限制", "约束", "要求", "保持", "兼容",
    "must", "must not", "never", "only", "constraint",
)
private val WORK_DECISION_CUES = listOf(
    "决定", "确认", "采用", "改为", "保留", "结论", "方案", "选择",
    "decide", "confirmed", "adopt", "keep", "conclusion",
)
private val WORK_FAILURE_CUES = listOf(
    "失败", "报错", "错误", "异常", "超时", "冲突", "回退", "无法", "风险", "未通过",
    "failure", "failed", "error", "timeout", "conflict", "rollback", "risk",
)

/**
 * Only an explicit, broad user cancellation clears earlier decisions. A reference to "旧方案"
 * alone is not enough: unrelated approved decisions must not silently disappear.
 */
internal fun isExplicitWorkDecisionReset(text: String): Boolean =
    WORK_DECISION_RESET_PATTERN.containsMatchIn(text.lowercase())

private val WORK_DECISION_RESET_PATTERN = Regex(
    """(?:此前|之前|先前|原有|旧的).{0,12}(?:全部|所有|一律).{0,8}(?:方案|决定|决策|结论).{0,12}(?:作废|废弃|撤销|取消|不再采用)|(?:此前|之前|先前|原有|旧的).{0,12}(?:方案|决定|决策|结论).{0,8}(?:全部|所有|一律).{0,8}(?:作废|废弃|撤销|取消|不再采用)""",
)

/**
 * A proposal or a mention of a plan is not a committed decision. Only an explicit user
 * instruction may enter the authoritative decision checkpoint; assistant suggestions stay in
 * the ordinary history/progress stream until the user approves them.
 */
internal fun isExplicitUserWorkDecision(text: String): Boolean =
    USER_DECISION_COMMIT_PATTERN.containsMatchIn(text.trim())

private val USER_DECISION_COMMIT_PATTERN = Regex(
    """(?:^|[，,；;。！？!?\\s])(?:确认(?:采用|使用|选择|按)|决定(?:采用|使用|选择|改为|按)|采用|改为|就按|按(?:这个|此|该)方案|现在(?:改为|采用|使用)|后续(?:改为|采用|使用)|我(?:们)?(?:确认|决定|选择)|请(?:采用|按这个|按此|改为))""",
)

/**
 * Broad revocation is intentionally explicit. Ambiguous "old plan has risks" messages
 * must not accidentally revoke unrelated, still-active requirements.
 */
internal fun isExplicitWorkConstraintReset(text: String): Boolean =
    WORK_CONSTRAINT_RESET_PATTERN.containsMatchIn(text.lowercase())

private val WORK_CONSTRAINT_RESET_PATTERN = Regex(
    """(?:此前|之前|先前|原有|旧的).{0,16}(?:全部|所有|一律).{0,8}(?:约束|要求|限制|规则).{0,12}(?:作废|废弃|撤销|取消|不再适用)|(?:此前|之前|先前|原有|旧的).{0,16}(?:约束|要求|限制|规则).{0,8}(?:全部|所有|一律).{0,8}(?:作废|废弃|撤销|取消|不再适用)""",
)

internal fun hasNewWorkConstraintAfterReset(text: String): Boolean =
    Regex("""(?:现在|今后|后续|从现在起|新(?:的)?要求|新(?:的)?约束|改为).{0,80}(?:必须|禁止|不能|不要|要求|保持|兼容|仅限|只允许)""")
        .containsMatchIn(text)
