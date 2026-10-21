package com.labteto.dshmobile.local.chat

import kotlin.coroutines.cancellation.CancellationException

internal object ChatReplyImmersionGuard {
    suspend fun <T> enforce(
        persona: PersonaProfile,
        initial: T,
        contentOf: (T) -> String,
        retry: suspend (repairHint: String) -> T,
        isComplete: (T) -> Boolean = { true },
        onEvent: (action: String, violations: List<String>) -> Unit = { _, _ -> },
    ): T {
        if (!isComplete(initial)) return initial
        if (persona.isUnboundChatPersona()) return initial

        val initialViolations = PersonaImmersionPolicy.findReplyViolations(
            persona = persona,
            text = contentOf(initial),
        )
        if (initialViolations.isEmpty()) return initial

        onEvent("retry", initialViolations)
        val repaired = try {
            retry(repairHint(persona, initialViolations))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed optional style repair must not discard a completed foreground reply.
            onEvent("repair-failed-returned-original", initialViolations)
            return initial
        }
        if (!isComplete(repaired)) return repaired
        val remaining = PersonaImmersionPolicy.findReplyViolations(
            persona = persona,
            text = contentOf(repaired),
        )
        if (remaining.isEmpty()) {
            onEvent("repaired", initialViolations)
            return repaired
        }

        // Record the violation; a heuristically judged style defect should not block a
        // successful model response after the one permitted repair attempt.
        onEvent("accepted-with-warning", remaining)
        // Style heuristics are advisory after an unsuccessful repair: keep the
        // original character voice rather than substituting another violation.
        return initial
    }

    private fun repairHint(
        persona: PersonaProfile,
        violations: List<String>,
    ): String = buildString {
        appendLine("【角色沉浸修复】")
        appendLine("当前人物：【${persona.name}】。")
        appendLine("上一版出现了脱离人物身份或聊天背景的内容，请依据当前人设与背景重新回复。")
        if (violations.isNotEmpty()) {
            appendLine("检测到的脱戏片段：${violations.take(4).joinToString("；")}")
        }
    }.trim()
}
