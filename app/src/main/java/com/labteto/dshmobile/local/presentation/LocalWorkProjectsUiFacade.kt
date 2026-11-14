package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.work.LocalWorkProjectStore
import javax.inject.Inject
import javax.inject.Singleton

/** Work-owned project management API. UI cannot mutate the project catalog or workspace directly. */
@Singleton
class LocalWorkProjectsUiFacade @Inject internal constructor(
    private val store: LocalWorkProjectStore,
) {
    val snapshots = store.snapshots
    fun create(name: String, root: String) = store.create(name, root)
    fun importExisting(name: String, root: String, sessionId: String?) =
        store.importExisting(name, root, sessionId)
    fun rename(id: String, name: String) = store.rename(id, name)
    fun update(id: String, name: String, root: String) = store.update(id, name, root)
    fun changeRoot(id: String, root: String) = store.changeRoot(id, root)
    fun moveDirectory(id: String, expectedRoot: String, root: String) =
        store.moveDirectory(id, expectedRoot, root)
    fun move(id: String, index: Int) = store.move(id, index)
    fun delete(id: String) = store.delete(id)
    fun assign(ids: Set<String>, id: String?) = store.assign(ids, id)
    fun forgetDeletedSessions(ids: Set<String>) = store.forgetDeletedSessions(ids)
}
