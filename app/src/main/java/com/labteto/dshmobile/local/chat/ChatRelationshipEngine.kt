package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Projects verified relationship context only. It neither routes slash commands nor
 * chooses how the character should respond. Missing relationship facts produce no prompt.
 */
@Singleton
class ChatRelationshipEngine @Inject constructor() {
    @Suppress("UNUSED_PARAMETER")
    fun prompt(input: String, state: ChatCharacterState): String {
        val lines = mutableListOf<String>()
        val status = state.relationshipState.trim()
        if (status.isNotBlank() && status !in setOf("熟悉中", "熟悉", "刚认识", "初识")) {
            lines += "当前关系：${status.take(120)}"
        }
        state.dynamics.unresolvedConflict.takeIf(String::isNotBlank)
            ?.let { lines += "尚未解决的事：${it.take(240)}" }
        state.dynamics.facts.takeLast(8).forEach { fact ->
            lines += "已有事实：${fact.text}"
        }
        state.dynamics.hypotheses.takeLast(5).forEach { impression ->
            lines += "尚未确认的印象：${impression.text}"
        }
        state.dynamics.unknowns.takeLast(5).takeIf(List<String>::isNotEmpty)
            ?.let { lines += "仍未知：${it.joinToString("；")}" }
        state.dynamics.sharedMoments.takeLast(6).forEach { event ->
            lines += "共同经历：$event"
        }
        state.dynamics.sharedObjects.takeLast(2).takeIf(List<String>::isNotEmpty)
            ?.let { lines += "共同物与约定：${it.joinToString("；")}" }
        if (lines.isEmpty()) return ""
        return (listOf("【人物关系与共同经历】") + lines).joinToString("\n")
    }
}
