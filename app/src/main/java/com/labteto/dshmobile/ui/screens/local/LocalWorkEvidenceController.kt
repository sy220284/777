package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalRequirementUiTarget
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor
import com.labteto.dshmobile.local.presentation.LocalWorkHistoryRecord
import com.labteto.dshmobile.local.work.LocalRequirementEvidenceLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class LocalWorkEvidenceState(
    val records: List<LocalWorkHistoryRecord> = emptyList(),
    val older: LocalWorkHistoryCursor? = null,
    val activities: List<LocalToolActivityUiItem> = emptyList(),
    val links: List<LocalRequirementEvidenceLink> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val invalidated: Boolean = false,
    val linking: Boolean = false,
    val linkAccepted: Boolean? = null,
    val linksFailed: Boolean = false,
    val inspected: LocalArtifactUiItem? = null,
)

/** The sheet owns only reads/cursors. Session facts and writes stay behind Work's action boundary. */
internal class LocalWorkEvidenceController(
    parent: CoroutineScope,
    private val sessionId: String,
    private val actions: LocalWorkFeatureUiActions,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutable = MutableStateFlow(LocalWorkEvidenceState())
    val state = mutable.asStateFlow()
    private var revision = 0L
    private var reading: Job? = null
    private var appendRequest = false
    fun close() = scope.cancel()
    fun refresh() = read(append = false)
    fun retry() = read(appendRequest)
    fun older() { if (!state.value.loading && state.value.older != null) read(append = true) }
    private fun read(append: Boolean) {
        appendRequest = append
        val request = ++revision
        reading?.cancel()
        val previous = state.value
        // A refresh supersedes the previous inspection/link UI generation.
        // The former operation may finish later, but must not keep this view busy.
        mutable.update { it.copy(loading = true, failed = false, inspected = null,
            linkAccepted = null, linking = false) }
        reading = scope.launch {
            try {
                val (page, activities) = withContext(Dispatchers.IO) {
                    actions.historyPage(sessionId, if (append) previous.older else null) to
                        if (append) previous.activities else actions.toolActivities(sessionId)
                }
                if (request != revision) return@launch
                if (page.invalidated) {
                    mutable.update { it.copy(loading = false, invalidated = true, records = emptyList(), older = null, inspected = null, activities = emptyList(), links = emptyList()) }
                } else mutable.update { it.copy(
                    records = ((if (append) previous.records else emptyList()) + page.records).distinctBy { it.sequence },
                    older = page.older, activities = activities,
                    loading = false, invalidated = false) }
                if (!append && !page.invalidated) {
                    try {
                        val links = withContext(Dispatchers.IO) { actions.requirementEvidence(sessionId) }
                        if (request == revision) mutable.update { it.copy(links = links, linksFailed = false) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (request == revision) mutable.update { it.copy(linksFailed = true) } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request == revision) mutable.update { it.copy(loading = false, failed = true) } }
        }
    }
    fun dismissInspection() { if (!state.value.linking) mutable.update { it.copy(inspected = null, linkAccepted = null) } }
    fun inspect(item: LocalArtifactUiItem) {
        if (state.value.linking) return
        val request = revision
        mutable.update { it.copy(linking = true, inspected = null, linkAccepted = null) }
        scope.launch {
            try {
                val inspected = withContext(Dispatchers.IO) { actions.inspectArtifact(sessionId, item) }
                if (request == revision) mutable.update { it.copy(inspected = inspected, linkAccepted = if (inspected == null) false else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request == revision) mutable.update { it.copy(linkAccepted = false) } }
            finally {
                if (request == revision) mutable.update { it.copy(linking = false) }
            }
        }
    }
    fun link(target: LocalRequirementUiTarget, item: LocalArtifactUiItem) {
        if (state.value.linking) return
        val request = revision
        mutable.update { it.copy(linking = true, linkAccepted = null) }
        scope.launch {
            try {
                val accepted = withContext(Dispatchers.IO) { actions.linkRequirementEvidence(sessionId, target, item) }
                if (request == revision) mutable.update { it.copy(linkAccepted = accepted) }
                if (accepted) {
                    try {
                        val links = withContext(Dispatchers.IO) { actions.requirementEvidence(sessionId) }
                        if (request == revision) mutable.update { it.copy(links = links, linksFailed = false) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (request == revision) mutable.update { it.copy(linksFailed = true) } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request == revision) mutable.update { it.copy(linkAccepted = false) } }
            finally {
                if (request == revision) mutable.update { it.copy(linking = false) }
            }
        }
    }
}
