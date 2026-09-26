package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessMessage

internal data class ChatProactiveDecision(
    val shouldSend: Boolean,
    val reason: String? = null,
)

private const val PROACTIVE_MIN_RETRY_GAP_MS = 6L * 60L * 60L * 1000L
private const val MAX_RECENT_PROACTIVE_FOR_CONTEXT = 5

internal fun evaluateChatProactivePolicy(
    messages: List<LocalHarnessMessage>,
    nowMillis: Long,
): ChatProactiveDecision {
    val dialogue = messages.filter { it.role == "user" || it.role == "assistant" }
    if (dialogue.isEmpty()) return ChatProactiveDecision(shouldSend = true)

    val lastUserAt = dialogue.lastOrNull { it.role == "user" }?.createdAt ?: Long.MIN_VALUE
    val proactiveSinceLastUser = dialogue.filter {
        it.role == "assistant" && it.proactive && it.createdAt > lastUserAt
    }

    if (proactiveSinceLastUser.size >= 2) {
        return ChatProactiveDecision(
            shouldSend = false,
            reason = "连续两次主动互动后用户还没有回复，已进入冷却",
        )
    }

    val lastProactiveAt = proactiveSinceLastUser.lastOrNull()?.createdAt
    if (
        lastProactiveAt != null &&
        nowMillis - lastProactiveAt < PROACTIVE_MIN_RETRY_GAP_MS
    ) {
        return ChatProactiveDecision(
            shouldSend = false,
            reason = "距离上次主动互动过近，暂缓本次互动",
        )
    }

    return ChatProactiveDecision(shouldSend = true)
}

internal fun proactiveConversationFocus(
    messages: List<LocalHarnessMessage>,
    fallback: String,
): String {
    val recent = messages
        .asReversed()
        .filter { it.role == "user" || (it.role == "assistant" && !it.proactive) }
        .take(4)
        .asReversed()
        .map { it.content.trim() }
        .filter(String::isNotBlank)

    return recent.joinToString("\n")
        .takeIf(String::isNotBlank)
        ?.take(2_400)
        ?: fallback
}

internal fun recentProactiveAvoidanceContext(
    messages: List<LocalHarnessMessage>,
): String {
    val recent = messages
        .asSequence()
        .filter { it.role == "assistant" && it.proactive }
        .map { it.content.trim().replace("\n", " ") }
        .filter(String::isNotBlank)
        .toList()
        .takeLast(MAX_RECENT_PROACTIVE_FOR_CONTEXT)

    if (recent.isEmpty()) return ""
    return buildString {
        appendLine("【最近主动互动，避免复读】")
        recent.forEachIndexed { index, text ->
            append(index + 1)
            append(". ")
            appendLine(text.take(180))
        }
        append("本次换一个自然切入点，不复用相同开场、主题或句式。")
    }
}

internal fun isNearDuplicateProactive(
    candidate: String,
    messages: List<LocalHarnessMessage>,
): Boolean {
    val normalizedCandidate = normalizeProactiveText(candidate)
    if (normalizedCandidate.length < 8) return false

    return messages
        .asSequence()
        .filter { it.role == "assistant" && it.proactive }
        .map { normalizeProactiveText(it.content) }
        .filter { it.length >= 8 }
        .takeLast(MAX_RECENT_PROACTIVE_FOR_CONTEXT)
        .any { previous ->
            if (
                normalizedCandidate.contains(previous) ||
                previous.contains(normalizedCandidate)
            ) {
                val shorter = minOf(normalizedCandidate.length, previous.length)
                val longer = maxOf(normalizedCandidate.length, previous.length)
                shorter.toDouble() / longer.toDouble() >= 0.78
            } else {
                bigramJaccard(normalizedCandidate, previous) >= 0.72
            }
        }
}

private fun normalizeProactiveText(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }

private fun bigramJaccard(left: String, right: String): Double {
    val leftSet = left.windowed(size = 2, step = 1, partialWindows = false).toSet()
    val rightSet = right.windowed(size = 2, step = 1, partialWindows = false).toSet()
    if (leftSet.isEmpty() || rightSet.isEmpty()) return 0.0
    val union = leftSet union rightSet
    if (union.isEmpty()) return 0.0
    return (leftSet intersect rightSet).size.toDouble() / union.size.toDouble()
}
