package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionRuntime
import com.labteto.dshmobile.local.uiBindingIdentity
import com.labteto.dshmobile.uicontract.UiPersonaConflict
import com.labteto.dshmobile.uicontract.UiPersonaInspection
import com.labteto.dshmobile.uicontract.UiPersonaInspectionReceipt
import com.labteto.dshmobile.uicontract.UiPersonaSuggestion
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Inspection freezes the real route and archive revision. The model cannot commit its findings. */
@Singleton
internal class LocalPersonaInspectionOwner @Inject constructor(
    private val gallery: ChatPersonaGalleryStore,
    private val service: PersonaInspectionService,
    private val state: LocalRuntimeStateStore,
    private val session: LocalSessionRuntime,
) {
    suspend fun inspect(identity: UiSessionIdentity, galleryId: String, storyId: String?): UiPersonaInspectionReceipt =
        withContext(Dispatchers.IO) {
            try {
                val before = synchronized(state.foregroundRunHandle.lock) {
                    state.state.value.takeIf { current ->
                        !state.sessionTransitioning && !current.loading && !current.kernel.running &&
                            current.usageMode == LocalUsageMode.CHAT && current.modelState.configured &&
                            state.uiBindingIdentity(current) == identity
                    }
                } ?: return@withContext UiPersonaInspectionReceipt.Rejected
                val entry = gallery.findEntry(galleryId) ?: return@withContext UiPersonaInspectionReceipt.Rejected
                val story = if (storyId == null) entry.story(null) else entry.stories.firstOrNull { it.id == storyId }
                if (storyId != null && story == null) return@withContext UiPersonaInspectionReceipt.Rejected
                val archived = story?.let { gallery.loadStoryHistory(entry.id, it.id, 32).messages }.orEmpty()
                val recent = if (before.chat.galleryId == galleryId && before.chat.galleryStoryId == story?.id)
                    session.transcriptTailForUi(before.sessionId, 28) else emptyList()
                fun stillCurrent() = synchronized(state.foregroundRunHandle.lock) {
                    val current = state.state.value
                    !state.sessionTransitioning && state.uiBindingIdentity(current) == identity &&
                        current.modelState == before.modelState && current.usageMode == LocalUsageMode.CHAT &&
                        !current.loading && !current.kernel.running
                }
                if (!stillCurrent() || gallery.findEntry(galleryId)?.updatedAt != entry.updatedAt)
                    return@withContext UiPersonaInspectionReceipt.Rejected
                val dialogue = (archived + recent).distinctBy { galleryMessageArchiveKey(it) }
                val result = service.inspect(before.modelState.model, before.modelState.baseUrl,
                    before.modelState.modelSelection.activeProfileId, entry.persona, dialogue,
                    story?.chatContext?.storyStage.orEmpty(), story?.chatContext?.visibleStoryStages().orEmpty())
                if (!stillCurrent() || gallery.findEntry(galleryId)?.updatedAt != entry.updatedAt)
                    return@withContext UiPersonaInspectionReceipt.Rejected
                UiPersonaInspectionReceipt.Inspected(UiPersonaInspection(entry.id, entry.updatedAt,
                    result.conflicts.map { UiPersonaConflict(it.field, it.fixedValue, it.observedValue, it.reason) },
                    result.suggestions.map { UiPersonaSuggestion(it.field, it.value, it.evidence) }))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { UiPersonaInspectionReceipt.Failed(error.message) }
        }
}
