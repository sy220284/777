package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hard output gate for chat mode.
 *
 * Prompt instructions shape the answer; this guard enforces the exact high-risk phrases that
 * should never reach the transcript. A failed rewrite still gets a deterministic final scrub.
 */
internal object ChatStyleGuard {
    val bannedPhrases: List<String> = listOf(
        "我理解你的感受",
        "听起来你",
        "如果你愿意的话",
        "值得注意的是",
        "需要说明的是",
        "总体而言",
        "综合来看",
        "以下是",
        "首先、其次、最后",
        "建议你",
        "希望这些对你有帮助",
        "如果还有问题随时告诉我",
        "我会一直在这里",
        "谢谢你愿意和我分享",
        "让我们一起",
    )

    fun violations(text: String): List<String> =
        bannedPhrases.filter { phrase -> phrase in text }

    fun repairPrompt(candidate: String, violations: List<String>): String = buildString {
        appendLine("上一版聊天回复命中了禁止使用的 AI / 客服套话，请重新写一版。")
        appendLine("必须保留原本想表达的意思和当前情绪，但改成自然即时聊天，不要解释你在改写。")
        appendLine("禁止词：" + violations.joinToString("、"))
        appendLine("候选回复：")
        append(candidate.take(MAX_CANDIDATE_CHARS))
    }

    fun scrub(text: String): String {
        var result = text
        bannedPhrases.forEach { phrase -> result = result.replace(phrase, "") }
        return result
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .ifBlank { "……" }
    }

    fun withContent(reply: LocalModelReply, content: String): LocalModelReply {
        val message = JsonObject(reply.message + ("content" to JsonPrimitive(content)))
        return reply.copy(message = message, content = content)
    }

    private const val MAX_CANDIDATE_CHARS = 6_000
}
