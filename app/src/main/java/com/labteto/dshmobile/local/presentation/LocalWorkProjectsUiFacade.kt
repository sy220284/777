package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.work.LocalWorkProjectStore
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import javax.inject.Inject
import javax.inject.Singleton

/** Work-owned project management API. UI cannot mutate the project catalog or workspace directly. */
@Singleton
class LocalWorkProjectsUiFacade @Inject internal constructor(
    private val store: LocalWorkProjectStore,
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    val snapshots = store.snapshots
    fun create(name: String, root: String) = store.create(name, root)
    fun importExisting(name: String, root: String, sessionId: String?) =
        store.importExisting(name, root, sessionId)
    fun rename(id: String, name: String) = store.rename(id, name)
    fun update(id: String, name: String, root: String) = store.update(id, name, root)
    fun changeRoot(id: String, root: String) = store.changeRoot(id, root)
    /** Resolve historical session previews without mutating their original file references. */
    fun relocatedReadPath(sessionId: String, historicalPath: String): String? =
        store.relocatedReadPath(sessionId, historicalPath)
    fun moveDirectory(id: String, expectedRoot: String, root: String): Boolean {
        check(!runtimeStateStore.state.value.kernel.running &&
            !runtimeStateStore.sessionTransitioning &&
            runtimeStateStore.jobManager.snapshotInfos().none {
                it.status == "running" || it.status == "queued"
            }) { "存在正在执行或待执行的任务；请在任务结束后移动项目文件夹" }
        return store.moveDirectory(id, expectedRoot, root)
    }
    fun move(id: String, index: Int) = store.move(id, index)
    fun delete(id: String) = store.delete(id)
    fun assign(ids: Set<String>, id: String?) = store.assign(ids, id)
    fun forgetDeletedSessions(ids: Set<String>) = store.forgetDeletedSessions(ids)
}
