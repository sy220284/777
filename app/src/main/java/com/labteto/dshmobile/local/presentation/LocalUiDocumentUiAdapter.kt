package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalConversationUiPortComposition
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalUiDocumentGeneration
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalUiDocumentSessionStore
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.UiDocumentsSceneSnapshot
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.UiDocumentSnapshot
import com.labteto.dshmobile.uicontract.UiActionAvailability
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.GenerateUiDocumentCommand
import com.labteto.dshmobile.uicontract.UiDocumentReceipt
import com.labteto.dshmobile.uicontract.UiDocumentActionRequest
import com.labteto.dshmobile.uicontract.UiDocumentAction
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.initialFields
import com.labteto.dshmobile.uicontract.invalidInputFields
import com.labteto.dshmobile.local.uiBindingIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

/** Host adapter dispatches only closed intentions; it never executes arbitrary model actions. */
internal class LocalUiDocumentUiAdapter(
    scope: CoroutineScope,
    private val state: LocalRuntimeStateStore,
    private val conversations: LocalConversationUiPortComposition,
    private val generation: LocalUiDocumentGeneration,
    private val store: LocalUiDocumentSessionStore,
    private val drafts: ConversationDraftUiPort,
    private val knownScenes: Set<UiSceneId>,
    private val navigationScenes: Set<UiSceneId>,
    private val navigate: (UiSceneId) -> Unit,
) : UiDocumentUiPort {
    private data class Context(val identity: UiSessionIdentity?, val workMode: Boolean, val messages: Set<String>)
    private val contexts = state.state.map { snapshot -> Context(state.uiBindingIdentity(snapshot),
        snapshot.usageMode == LocalUsageMode.WORK, snapshot.messages.map { it.id }.toSet()) }.distinctUntilChanged()
    override val snapshots: StateFlow<UiDocumentsSceneSnapshot> = combine(contexts, store.revision, generation.generating) { context, _, busy ->
        try {
            val id = context.identity
            val records = id?.let { store.read(it.sessionId, context.messages, generation.budget, knownScenes) }.orEmpty()
            UiDocumentsSceneSnapshot(id?.sessionId, records.map { record ->
                val document = record.document
                val current = sourceMatches(document.source, id, context.workMode)
                UiDocumentSnapshot(document, record.provenance, document.actions.associate { action ->
                    val reason = when {
                        !current -> UiCommandRejection.STALE_IDENTITY
                        action is UiDocumentAction.AnswerApproval && conversations.work.snapshot().approval?.pendingId != action.pendingId -> UiCommandRejection.APPROVAL_EXPIRED
                        action is UiDocumentAction.OpenKnownScene && action.sceneId !in navigationScenes -> UiCommandRejection.UNSUPPORTED
                        else -> null
                    }
                    action.id to UiActionAvailability(reason == null, reason)
                })
            }, id?.sessionId in busy, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UiDocumentsSceneSnapshot(context.identity?.sessionId, emptyList(), false, "ui_document_history_unavailable")
        }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(5_000), UiDocumentsSceneSnapshot(null, emptyList(), false, null))

    override fun snapshot() = snapshots.value
    override suspend fun generate(command: GenerateUiDocumentCommand) = generation.generate(command, knownScenes, navigationScenes)

    override fun submit(identity: UiSessionIdentity, request: UiDocumentActionRequest): UiDocumentReceipt {
        return synchronized(state.foregroundRunHandle.lock) {
            if (conversations.identitySnapshot() != identity) return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            val workMode = state.state.value.usageMode == LocalUsageMode.WORK
            val record = snapshots.value.documents.firstOrNull { it.document.documentId == request.documentId }?.document
                ?: return UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            if (record.revision != request.revision || record.source != request.source || !sourceMatches(record.source, identity, workMode))
                return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            val defaults = record.initialFields()
            if (request.fields.keys.any { it !in defaults }) return UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            val action = record.actions.firstOrNull { it.id == request.actionId } ?: return UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            when (action) {
                is UiDocumentAction.SendUserInput -> {
                    val fields = defaults + request.fields
                    val invalid = record.invalidInputFields(action, fields)
                    if (invalid.isNotEmpty()) return UiDocumentReceipt.InvalidFields(invalid)
                    val text = buildString {
                        append(action.text)
                        action.fieldIds.forEach { id -> append('\n'); append(id); append(": "); append(fields.getValue(id)) }
                    }
                    if (text.isBlank()) UiDocumentReceipt.Rejected(UiCommandRejection.EMPTY_INPUT)
                    else { drafts.updateDraft(identity.sessionId, text); UiDocumentReceipt.DraftPrepared }
                }
                is UiDocumentAction.OpenKnownScene -> {
                    if (action.sceneId !in navigationScenes) UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                    else { navigate(action.sceneId); UiDocumentReceipt.NavigationRequested }
                }
                is UiDocumentAction.AnswerApproval -> UiDocumentReceipt.OwnerReceipt(
                    conversations.work.submit(WorkConversationUiCommand.AnswerApproval(identity, action.pendingId, action.approved)))
            }
        }
    }

    private fun sourceMatches(source: UiDocumentSource, identity: UiSessionIdentity?, workMode: Boolean) = identity != null &&
        source.sessionId == identity.sessionId && source.contextGeneration == identity.contextGeneration &&
        source.profileId == identity.profileId && source.projectId == identity.projectId && source.workMode == workMode
}

@javax.inject.Singleton
class LocalUiDocumentUiPortFactory @javax.inject.Inject internal constructor(
    private val state: LocalRuntimeStateStore,
    private val conversations: LocalConversationUiPortComposition,
    private val generation: LocalUiDocumentGeneration,
    private val store: LocalUiDocumentSessionStore,
) {
    fun create(scope: CoroutineScope, drafts: ConversationDraftUiPort, knownScenes: Set<UiSceneId>,
        navigationScenes: Set<UiSceneId> = knownScenes,
        navigate: (UiSceneId) -> Unit): UiDocumentUiPort =
        LocalUiDocumentUiAdapter(scope, state, conversations, generation, store, drafts, knownScenes, navigationScenes, navigate)
}
