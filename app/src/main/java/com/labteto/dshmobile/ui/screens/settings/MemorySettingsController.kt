package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.local.memory.MemoryManagementCursor
import com.labteto.dshmobile.local.memory.MemoryManagementPage
import com.labteto.dshmobile.local.memory.MemoryManagementQuery
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.presentation.LocalSettingsMemoryContext
import com.labteto.dshmobile.local.session.LocalConversationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class MemoryManagementUiState(
    val records: List<MemoryRecord> = emptyList(),
    val query: String = "",
    val kinds: Set<MemoryKind> = MemoryKind.entries.toSet(),
    val next: MemoryManagementCursor? = null,
    val totalMatching: Int = 0,
    val kindCounts: Map<MemoryKind, Int> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val busyIds: Set<String> = emptySet(),
)

/** Query ownership stays in Memory. This controller owns only UI requests and stale-result rejection. */
internal class MemorySettingsController(
    private val scope: CoroutineScope,
    private val context: () -> LocalSettingsMemoryContext,
    private val read: (MemoryManagementQuery) -> MemoryManagementPage,
    private val updateRecord: (MemoryRecord, String, Boolean) -> MemoryRecord,
    private val forgetRecord: (MemoryRecord) -> Boolean,
) {
    private val mutable = MutableStateFlow(MemoryManagementUiState())
    val state = mutable.asStateFlow()
    private var selectedMessageSource: Pair<String, String>? = null
    private var request = 0L
    private var reading: Job? = null

    fun focusSourceMessage(sessionId: String?, messageId: String?) {
        selectedMessageSource = if (!sessionId.isNullOrBlank() && !messageId.isNullOrBlank()) sessionId to messageId else null
        refresh()
    }

    fun search(text: String, kinds: Set<MemoryKind>) {
        if (state.value.query == text && state.value.kinds == kinds) return
        mutable.update { it.copy(query = text, kinds = kinds) }
        refresh()
    }

    fun refresh() = load(append = false)
    fun loadOlder() { if (!state.value.loading && state.value.next != null) load(append = true) }

    private fun load(append: Boolean) {
        val revision = ++request
        reading?.cancel()
        val previous = state.value
        val local = context()
        val scopes = when (local.conversationMode) {
            LocalConversationMode.INDEPENDENT -> setOf(MemoryScope.GLOBAL)
            LocalConversationMode.PROJECT -> setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT)
            LocalConversationMode.CONTINUATION -> setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT, MemoryScope.LINEAGE)
        }
        val query = MemoryManagementQuery(scopes, local.projectId, local.lineageId,
            selectedMessageSource?.first, selectedMessageSource?.second, previous.query, previous.kinds,
            if (append) previous.next else null)
        mutable.update { if (append) it.copy(loading = true, error = null) else it.copy(
            records = emptyList(), next = null, totalMatching = 0, kindCounts = emptyMap(), loading = true, error = null) }
        reading = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { read(query) }
                if (request != revision) return@launch
                mutable.update { it.copy(records = ((if (append) previous.records else emptyList()) + page.records)
                    .distinctBy(MemoryRecord::id), next = page.next, totalMatching = page.totalMatching,
                    kindCounts = page.kindCounts, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request == revision) mutable.update { it.copy(loading = false, error = error.message ?: "记忆读取失败，请重试") }
            }
        }
    }

    fun update(id: String, content: String, pinned: Boolean, onDone: (String?) -> Unit = {}) =
        mutate(id, onDone) { updateRecord(it, content, pinned); true }

    fun forget(id: String, onDone: (String?) -> Unit = {}) = mutate(id, onDone, forgetRecord)

    private fun mutate(id: String, onDone: (String?) -> Unit, write: (MemoryRecord) -> Boolean) {
        if (id in state.value.busyIds) return
        val current = state.value.records.firstOrNull { it.id == id }
        if (current == null) { onDone("记忆已经变化，请刷新后重试"); return }
        mutable.update { it.copy(busyIds = it.busyIds + id) }
        scope.launch {
            val error = try {
                if (withContext(Dispatchers.IO) { write(current) }) null else "记忆已变化或已停用，请刷新后重试"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failure.message ?: "记忆保存失败，请重试" }
            finally { mutable.update { it.copy(busyIds = it.busyIds - id) } }
            refresh()
            onDone(error)
        }
    }
}
