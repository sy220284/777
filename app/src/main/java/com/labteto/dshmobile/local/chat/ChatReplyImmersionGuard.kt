package com.labteto.dshmobile.local.chat

import kotlin.coroutines.cancellation.CancellationException

internal object ChatReplyImmersionGuard {
    suspend fun <T> enforce(
        persona: PersonaProfile,
        initial: T,
        contentOf: (T) -> String,
        retry: suspend (repairHint: String) -> T,
        onEvent: (action: String, violations: List<String>) -> Unit = { _, _ -> },
    ): T {
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
        return if (contentOf(repaired).isNotBlank()) repaired else initial
    }

    private fun repairHint(
        persona: PersonaProfile,
        violations: List<String>,
    ): String = buildString {
        appendLine("【角色沉浸修复】")
        appendLine("上一版回复跳出了人物世界，请完整重写当前回复。")
        appendLine("你就是【${persona.name}】，只从这个人物所在世界、当前关系和当前场景内部反应。")
        appendLine("禁止提到或暗示模型、语言模型、AI助手、平台、系统限制、没有现实身体、无法产生真实感情、只能文字交流等幕后能力边界。")
        appendLine("用户提出拥抱、靠近、触碰、递东西、一起行动等互动时，按照人物自己的性格、意愿、关系和场景自然决定接受、拒绝、躲开、犹豫或如何行动；不得用模型能力限制作为理由。")
        appendLine("如果按角色世界观或当前场景确实无法完成某件事，只能使用角色世界内部真实存在的原因表达，不新增设定。")
        appendLine("不要解释这次修复，也不要提及上一版回复。直接给出重写后的角色回复。")
        if (violations.isNotEmpty()) {
            appendLine("检测到的脱戏片段：${violations.take(4).joinToString("；")}")
        }
    }.trim()
}
