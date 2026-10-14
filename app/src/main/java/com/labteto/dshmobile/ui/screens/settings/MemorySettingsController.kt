package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.uicontract.MemoryUiPort
import com.labteto.dshmobile.uicontract.MemoryUiCommand
import com.labteto.dshmobile.uicontract.MemoryUiReceipt
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.local.session.LocalConversationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns Settings-facing local-memory scope, list and mutation behavior. */
internal class MemorySettingsController(
    private val localHarness: SettingsUiPort,
    private val settingsData: MemoryUiPort,
) {
    private val _memories = MutableStateFlow<List<MemoryRecord>>(emptyList())
    private var selectedMessageSource: Pair<String, String>? = null
    val memories: StateFlow<List<MemoryRecord>> = _memories.asStateFlow()

    fun focusSourceMessage(sessionId: String?, messageId: String?) {
        selectedMessageSource = if (!sessionId.isNullOrBlank() && !messageId.isNullOrBlank()) {
            sessionId to messageId
        } else null
        refresh()
    }

    fun refresh() {
        selectedMessageSource?.let { (sessionId, messageId) ->
            _memories.value = settingsData.memoriesFromSourceMessage(sessionId, messageId)
            return
        }
        val local = localHarness.memoryContext()
        val scopes = when (local.conversationMode) {
            LocalConversationMode.INDEPENDENT -> setOf(MemoryScope.GLOBAL)
            LocalConversationMode.PROJECT -> setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT)
            LocalConversationMode.CONTINUATION ->
                setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT, MemoryScope.LINEAGE)
        }
        _memories.value = settingsData.memories(scopes, local.projectId, local.lineageId)
    }

    fun update(
        id: String,
        content: String,
        pinned: Boolean,
        onDone: (String?) -> Unit = {},
    ) {
        val current = _memories.value.firstOrNull { it.id == id }
        if (current == null) {
            onDone("记忆已经不存在")
            refresh()
            return
        }
        runCatching {
            when (val receipt = settingsData.submit(MemoryUiCommand.Update(current, content, pinned))) {
                is MemoryUiReceipt.Updated -> receipt.record
                is MemoryUiReceipt.Failed -> throw IllegalStateException(receipt.message)
                else -> error("Memory revision was not updated")
            }
        }.onSuccess {
            refresh()
            onDone(null)
        }.onFailure { error ->
            refresh()
            onDone(error.message ?: "更新记忆失败")
        }
    }

    fun forget(id: String, onDone: (String?) -> Unit = {}) {
        val current = _memories.value.firstOrNull { it.id == id }
        if (current == null) {
            onDone("记忆已经不存在或已停用")
            refresh()
            return
        }
        runCatching { when (val receipt = settingsData.submit(MemoryUiCommand.Forget(current))) {
                MemoryUiReceipt.Forgotten -> true
                MemoryUiReceipt.StaleOrMissing -> false
                is MemoryUiReceipt.Failed -> throw IllegalStateException(receipt.message)
                else -> false
            } }
            .onSuccess { removed ->
                refresh()
                onDone(if (removed) null else "这条记忆已被停用或不存在")
            }
            .onFailure { error ->
                refresh()
                onDone(error.message ?: "停用记忆失败")
            }
    }
}
