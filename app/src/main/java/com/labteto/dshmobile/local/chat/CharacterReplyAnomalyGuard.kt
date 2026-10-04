package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.quality.LocalOutputQualityContext
import com.labteto.dshmobile.local.quality.LocalOutputQualityGuard
import com.labteto.dshmobile.local.quality.LocalOutputQualityResult

internal data class CharacterReplyAnomalyResult(
    val text: String,
    val anomalies: List<String> = emptyList(),
)

internal object CharacterReplyAnomalyGuard : LocalOutputQualityGuard {
    fun repair(text: String): CharacterReplyAnomalyResult {
        val result = inspect(text, LocalOutputQualityContext())
        return CharacterReplyAnomalyResult(result.text, result.findings)
    }

    override fun inspect(
        text: String,
        context: LocalOutputQualityContext,
    ): LocalOutputQualityResult {
        if (text.isBlank()) return LocalOutputQualityResult(text)
        val anomalies = linkedSetOf<String>()
        var lines = text.lines()
        val hadMetaHeading = lines.firstOrNull()?.trim()?.matches(META_HEADING) == true
        if (hadMetaHeading) {
            anomalies += "解释式标题"
            lines = lines.drop(1)
        }
        val structuredCount = lines.count { STRUCTURED_LINE.containsMatchIn(it) }
        if (structuredCount >= 4 && hadMetaHeading) {
            anomalies += "过度罗列"
            lines = lines.map { it.replace(STRUCTURED_LINE, "") }
        } else if (structuredCount >= 6) {
            anomalies += "可能过度罗列"
        }
        val deduped = mutableListOf<String>()
        lines.forEach { line ->
            if (deduped.lastOrNull()?.trim() == line.trim() && line.isNotBlank()) {
                anomalies += "连续重复"
            } else {
                deduped += line
            }
        }
        val candidate = deduped.joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .ifBlank { text.trim() }
        val questionCount = Regex("[?？]").findAll(candidate).count()
        if (questionCount >= 4) anomalies += "连续反问"
        val lengths = SENTENCE.findAll(candidate).map { it.value.length }.toList()
        if (
            candidate.length >= 320 &&
            lengths.size >= 5 &&
            (lengths.maxOrNull() ?: 0) - (lengths.minOrNull() ?: 0) <= 8
        ) {
            anomalies += "句式过度整齐"
        }
        return LocalOutputQualityResult(
            text = candidate,
            findings = anomalies.toList(),
            changed = candidate != text,
        )
    }

    private val META_HEADING = Regex("""^(?:总结|建议|分析|结论|回复建议|我的看法)[：:]?$""")
    private val STRUCTURED_LINE = Regex("""^\s*(?:[-*•]|\d{1,2}[.、)])\s*""")
    private val SENTENCE = Regex("""[^。！？!?\n]+[。！？!?]?""")
}
