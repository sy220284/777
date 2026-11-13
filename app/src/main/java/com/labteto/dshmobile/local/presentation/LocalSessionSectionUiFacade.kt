package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.session.LocalSessionSectionSnapshot
import com.labteto.dshmobile.local.session.LocalSessionSectionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session feature boundary exposed to UI. The durable store stays owned by local.session.
 * No UI code can mutate persisted section records without going through this facade.
 */
@Singleton
class LocalSessionSectionUiFacade @Inject constructor(
    private val store: LocalSessionSectionStore,
) {
    val snapshots = store.snapshots

    fun create(title: String) = store.create(title)
    fun rename(id: String, title: String) = store.rename(id, title)
    fun move(id: String, targetIndex: Int) = store.move(id, targetIndex)
    fun delete(id: String) = store.delete(id)
    fun assign(sessionIds: Set<String>, sectionId: String?) = store.assign(sessionIds, sectionId)
    fun forgetDeletedSessions(sessionIds: Set<String>) = store.forgetDeletedSessions(sessionIds)
}
