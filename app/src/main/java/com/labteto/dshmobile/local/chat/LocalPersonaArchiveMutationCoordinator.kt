package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.uicontract.DeletePersonaGalleryCommand
import com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort
import com.labteto.dshmobile.uicontract.PersonaStoryUiCommand
import com.labteto.dshmobile.uicontract.PersonaStoryUiPort
import com.labteto.dshmobile.uicontract.UiPersonaGalleryDeletionReceipt
import com.labteto.dshmobile.uicontract.UiPersonaStoryReceipt
import com.labteto.dshmobile.uicontract.UiStorySessionSync
import kotlinx.coroutines.CancellationException

/**
 * Chat/Gallery Feature orchestration, not a Legacy UI callback.
 *
 * A committed archive mutation is NEVER repeated to repair a failed UI refresh
 * or deferred live-session synchronization. A failed follow-up refresh is
 * reported separately; the persisted edit/deletion remains committed.
 */
internal class LocalPersonaArchiveMutationCoordinator(
    private val stories: PersonaStoryUiPort,
    private val gallery: PersonaGalleryDeletionUiPort,
    private val refresh: suspend () -> Unit,
) : com.labteto.dshmobile.uicontract.PersonaArchiveMutationUiPort {
    override suspend fun saveStory(command: PersonaStoryUiCommand):
        com.labteto.dshmobile.uicontract.UiPersonaArchiveMutationReceipt =
        when (val saved = stories.submit(command)) {
            is UiPersonaStoryReceipt.Saved -> {
                val refreshFailed = refreshCommittedArchive()
                com.labteto.dshmobile.uicontract.UiPersonaArchiveMutationReceipt.Committed(
                    sessionSyncDeferred = saved.sessionSync == UiStorySessionSync.DEFERRED ||
                        refreshFailed,
                )
            }
            UiPersonaStoryReceipt.Missing -> com.labteto.dshmobile.uicontract.UiPersonaArchiveMutationReceipt.Missing
            is UiPersonaStoryReceipt.Failed -> com.labteto.dshmobile.uicontract.UiPersonaArchiveMutationReceipt.Rejected(saved.message)
        }

    override suspend fun deleteGallery(command: DeletePersonaGalleryCommand):
        com.labteto.dshmobile.uicontract.UiPersonaArchiveMutationReceipt =
        when (val deleted = gallery.delete(command)) {
            is UiPersonaGalleryDeletionReceipt.Deleted -> {
                val refreshFailed = refreshCommittedArchive()
                com.labteto.dshmobile.uicontract.UiPersonaArchiveMutationReceipt.Committed(
                    sessionSyncDeferred = deleted.sessionSync == UiStorySessionSync.DEFERRED ||
                        refreshFailed,
                    portraitCleanupWarning = deleted.portraitCleanupWarning,
                )
            }
            UiPersonaGalleryDeletionReceipt.Missing -> com.labteto.dshmobile.uicontract.UiPersonaArchiveMutationReceipt.Missing
            is UiPersonaGalleryDeletionReceipt.Failed -> com.labteto.dshmobile.uicontract.UiPersonaArchiveMutationReceipt.Rejected(deleted.message)
        }

    private suspend fun refreshCommittedArchive(): Boolean = try {
        refresh()
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }
}
