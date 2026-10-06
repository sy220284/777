package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.presentation.LocalSettingsDataFacade
import com.labteto.dshmobile.local.presentation.LocalSettingsRuntime
import com.labteto.dshmobile.local.session.LocalConversationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns Settings-facing local-memory scope, list and mutation behavior. */
internal class MemorySettingsController(
    private val localHarness: LocalSettingsRuntime,
    private val settingsData: LocalSettingsDataFacade,
) {
    private val _memories = MutableStateFlow<List<MemoryRecord>>(emptyList())
    val memories: StateFlow<List<MemoryRecord>> = _memories.asStateFlow()

    fun refresh() {
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
            settingsData.updateMemory(current, content, pinned)
        }.onSuccess {
            refresh()
            onDone(null)
        }.onFailure { error ->
            onDone(error.message ?: "更新记忆失败")
        }
    }

    fun forget(id: String, onDone: (String?) -> Unit = {}) {
        runCatching { settingsData.forgetMemory(id) }
            .onSuccess {
                refresh()
                onDone(null)
            }
            .onFailure { error -> onDone(error.message ?: "停用记忆失败") }
    }
}
