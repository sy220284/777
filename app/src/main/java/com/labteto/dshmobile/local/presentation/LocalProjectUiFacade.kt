package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.project.LocalProjectFeatureRuntime
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow ProjectFeature presentation API: no direct store or mutable domain state is exposed. */
@Singleton
class LocalProjectUiFacade @Inject internal constructor(
    private val runtime: LocalProjectFeatureRuntime,
    private val sessions: LocalSessionStorageRuntime,
    private val state: LocalRuntimeStateStore,
    private val memory: MemoryStore,
) {
    internal val catalog get() = runtime.catalog
    internal val recoveryNotice get() = runtime.recoveryNotice
    fun backupAndResetCatalog() = runtime.backupAndResetCatalog()
    fun create(name: String): String = runtime.create(name)
    fun select(id: String) = runtime.select(id)
    /** No deletion while a live/durable session or any active scoped memory owns this project ID. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runtime.delete(id) { projectId ->
            check(state.state.value.projectId != projectId &&
                sessions.coordinator.summaries().none { it.projectId == projectId }) {
                "该项目仍被会话引用，请先处理关联会话后再删除"
            }
            check(memory.listActive(setOf(MemoryScope.PROJECT), projectId, null, limit = 1).isEmpty()) {
                "该项目仍有长期记忆，请先移除关联记忆后再删除"
            }
        }
    }
    fun rename(id: String, name: String) = runtime.rename(id, name)
    fun updateInstructions(id: String, instructions: String) = runtime.updateInstructions(id, instructions)
}
