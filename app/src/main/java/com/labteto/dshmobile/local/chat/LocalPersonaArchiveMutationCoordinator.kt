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
) {
    sealed interface Receipt {
        data class Committed(
            val sessionSyncDeferred: Boolean,
            val portraitCleanupWarning: String? = null,
        ) : Receipt
        data object Missing : Receipt
        data class Rejected(val reason: String?) : Receipt
    }

    suspend fun saveStory(command: PersonaStoryUiCommand): Receipt =
        when (val saved = stories.submit(command)) {
            is UiPersonaStoryReceipt.Saved ->
                Receipt.Committed(
                    sessionSyncDeferred = saved.sessionSync == UiStorySessionSync.DEFERRED ||
                        refreshCommittedArchive(),
                )
            UiPersonaStoryReceipt.Missing -> Receipt.Missing
            is UiPersonaStoryReceipt.Failed -> Receipt.Rejected(saved.message)
        }

    suspend fun deleteGallery(command: DeletePersonaGalleryCommand): Receipt =
        when (val deleted = gallery.delete(command)) {
            is UiPersonaGalleryDeletionReceipt.Deleted ->
                Receipt.Committed(
                    sessionSyncDeferred = deleted.sessionSync == UiStorySessionSync.DEFERRED ||
                        refreshCommittedArchive(),
                    portraitCleanupWarning = deleted.portraitCleanupWarning,
                )
            UiPersonaGalleryDeletionReceipt.Missing -> Receipt.Missing
            is UiPersonaGalleryDeletionReceipt.Failed -> Receipt.Rejected(deleted.message)
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
