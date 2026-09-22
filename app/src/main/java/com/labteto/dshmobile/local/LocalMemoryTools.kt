package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.memory.*
import kotlinx.serialization.json.*

internal class LocalMemoryTools(
    private val store: MemoryStore,
    private val manager: MemoryManager,
    private val state: () -> LocalHarnessState,
    private val sessionId: () -> String,
) {
    fun execute(name: String, args: JsonObject, allowMutation: Boolean): String { return when (name) {
            "memory_search" -> {
                val state = state()
                val records = store.search(
                    query = args.string("query"),
                    allowedScopes = allowedMemoryScopes(state.conversationMode),
                    projectId = state.projectId,
                    lineageId = state.lineageId,
                    maxItems = 12,
                    maxChars = 8_000,
                )
                if (records.isEmpty()) {
                    "未找到当前作用域内的相关长期记忆"
                } else {
                    records.joinToString("\n") {
                        "[${it.scope.name.lowercase()}/${it.kind.name.lowercase()}] ${it.content}"
                    }
                }
            }
            "memory_list" -> {
                val state = state()
                val records = store.listActive(
                    allowedScopes = allowedMemoryScopes(state.conversationMode),
                    projectId = state.projectId,
                    lineageId = state.lineageId,
                    limit = 20,
                )
                if (records.isEmpty()) {
                    "当前作用域没有长期记忆"
                } else {
                    records.joinToString("\n") {
                        "[${it.scope.name.lowercase()}/${it.kind.name.lowercase()}] ${it.content.take(500)}"
                    }.take(10_000)
                }
            }
            "memory_remember" -> {
                if (!allowMutation) return "该子任务无权写入长期记忆"
                val state = state()
                val scope = when (args.string("scope").lowercase()) {
                    "global" -> MemoryScope.GLOBAL
                    "project" -> MemoryScope.PROJECT
                    "lineage" -> MemoryScope.LINEAGE
                    else -> return "记忆作用域必须为 global、project 或 lineage"
                }
                if (scope == MemoryScope.PROJECT && state.projectId == null) {
                    return "当前是独立对话，没有可写入的项目作用域"
                }
                if (
                    scope == MemoryScope.LINEAGE &&
                    state.conversationMode != LocalConversationMode.CONTINUATION
                ) {
                    return "只有“继续当前任务”对话可以写入 lineage 记忆，避免产生无法召回的幽灵记忆"
                }
                val kind = runCatching {
                    MemoryKind.valueOf((args.optionalString("kind") ?: "fact").uppercase())
                }.getOrDefault(MemoryKind.FACT)
                val record = runCatching {
                    manager.remember(
                        content = args.string("content"),
                        scope = scope,
                        kind = kind,
                        projectId = state.projectId,
                        lineageId = state.lineageId,
                        sourceSessionId = sessionId(),
                        importance = if (kind in setOf(MemoryKind.RULE, MemoryKind.CONSTRAINT, MemoryKind.DECISION)) 85 else 60,
                    )
                }.getOrElse { error ->
                    return error.message ?: "长期记忆写入失败"
                }
                "已保存长期记忆：${record.content}"
            }
            else -> error("未知记忆工具：$name")
        }
    }
    private fun JsonObject.string(key: String): String = optionalString(key)?.takeIf { it.isNotBlank() } ?: error("缺少参数：$key")
    private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun allowedMemoryScopes(mode: LocalConversationMode): Set<MemoryScope> = when (mode) {
        LocalConversationMode.INDEPENDENT -> setOf(MemoryScope.GLOBAL)
        LocalConversationMode.PROJECT -> setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT)
        LocalConversationMode.CONTINUATION -> MemoryScope.values().toSet()
    }
}
