package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalExecutionService
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore

/**
 * Chat-owned foreground turn dispatcher.
 *
 * Group/direct selection, explicit-correction capture and fresh relationship hydration belong to
 * ChatFeature. The direct turn body remains a temporary callback until runChatTurn is migrated.
 */
internal class LocalChatTurnDispatcher(
    private val context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val personaCorrections: LocalChatPersonaCorrectionCoordinator,
    private val relationshipHydrator: LocalChatRelationshipHydrator,
    private val groupExecutor: LocalGroupChatTurnExecutor,
    private val directTurn: suspend (input: String, sourceMessageId: String?) -> Unit,
) {
    internal suspend fun run(
        input: String,
        memoryInput: String = input,
        sourceMessageId: String? = null,
    ) {
        val snapshot = runtimeStateStore.state.value
        check(snapshot.usageMode == LocalUsageMode.CHAT) {
            "Chat 回合入口只能处理 Chat 模式"
        }

        if (snapshot.chat.groupChat.enabled) {
            personaCorrections.captureGroup(memoryInput)
            runOwnedGroupChatTurn(
                context = context,
                sessionId = snapshot.sessionId,
                currentSessionId = { runtimeStateStore.currentSessionId },
                currentError = { runtimeStateStore.state.value.error },
                executor = groupExecutor,
                input = input,
                sourceMessageId = sourceMessageId,
            )
            return
        }

        personaCorrections.captureDirect(memoryInput)
        relationshipHydrator.hydrate()
        LocalExecutionService.withTurn(
            context = context,
            sessionId = snapshot.sessionId,
            error = { runtimeStateStore.state.value.error },
        ) {
            directTurn(input, sourceMessageId)
        }
    }
}
