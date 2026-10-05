package com.labteto.dshmobile.local.runtime



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
