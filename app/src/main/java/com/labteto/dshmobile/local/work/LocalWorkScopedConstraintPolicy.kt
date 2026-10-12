package com.labteto.dshmobile.local.work

/**
 * Deliberately narrow authority operation: only a user-authored, explicitly quoted original
 * constraint can be selectively replaced. Semantic guesses are not authorization.
 */
internal data class LocalWorkScopedConstraintEdit(
    val previous: String,
    val replacement: String?,
)

internal fun parseLocalWorkScopedConstraintEdit(text: String): LocalWorkScopedConstraintEdit? {
    val normalized = text.trim()
    // A question, hypothetical or proposal is not a committed user directive.
    if (normalized.endsWith("?") || normalized.endsWith("？") ||
        Regex("""^(?:建议|讨论|考虑|假设|是否|能否)""").containsMatchIn(normalized)
    ) return null
    val replace = Regex(
        """(?:将|把)\s*[「『“"]([^」』”"]{1,240})[」』”"]\s*(?:改为|替换为|调整为)\s*[「『“"]([^」』”"]{1,240})[」』”"]""",
    ).find(text)
    if (replace != null) {
        val previous = replace.groupValues[1].trim()
        val replacement = replace.groupValues[2].trim()
        if (previous.isNotEmpty() && replacement.isNotEmpty() && previous != replacement) {
            return LocalWorkScopedConstraintEdit(previous, replacement)
        }
    }
    val remove = Regex(
        """(?:撤销|取消|废弃|删除)\s*[「『“"]([^」』”"]{1,240})[」』”"](?:这条)?(?:约束|要求|规则)?""",
    ).find(text) ?: return null
    return remove.groupValues[1].trim().takeIf(String::isNotBlank)?.let {
        LocalWorkScopedConstraintEdit(it, null)
    }
}

internal fun normalizedWorkConstraint(text: String): String =
    text.trim().replace(Regex("""\s+"""), " ").lowercase()

/**
 * Full user-text extraction may wrap a quoted constraint in a sentence. Keep the matching
 * conservative and phrase-bounded: do not match a one-character inferred topic.
 */
internal fun isRevokedWorkConstraint(constraint: String, tombstones: Collection<String>): Boolean {
    val normalized = normalizedWorkConstraint(constraint)
    return tombstones.any { target ->
        val phrase = normalizedWorkConstraint(target)
        phrase.length >= 4 && (normalized == phrase || normalized.contains(phrase))
    }
}
