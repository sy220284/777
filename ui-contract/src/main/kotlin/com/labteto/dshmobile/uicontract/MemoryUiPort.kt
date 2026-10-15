package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryManagementQuery
import com.labteto.dshmobile.local.memory.MemoryManagementPage
import com.labteto.dshmobile.local.memory.MemoryScope

sealed interface MemoryUiCommand {
    /** The Owner validates the supplied immutable revision before changing a record. */
    data class Update(val expected: MemoryRecord, val content: String, val pinned: Boolean) : MemoryUiCommand
    data class Forget(val expected: MemoryRecord) : MemoryUiCommand
}
sealed interface MemoryUiReceipt {
    data class Updated(val record: MemoryRecord) : MemoryUiReceipt
    data object Forgotten : MemoryUiReceipt
    data object StaleOrMissing : MemoryUiReceipt
    /** A derived diary cleanup failure may follow a committed memory mutation; never replay blindly. */
    data class Failed(val message: String?) : MemoryUiReceipt
}
interface MemoryUiPort {
    fun memoryPage(query: MemoryManagementQuery): MemoryManagementPage
    fun memories(scopes: Set<MemoryScope>, projectId: String?, lineageId: String?): List<MemoryRecord>
    fun memoriesFromSourceMessage(sessionId: String, messageId: String): List<MemoryRecord>
    fun submit(command: MemoryUiCommand): MemoryUiReceipt
}
