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
import com.labteto.dshmobile.uicontract.UiDocumentBinding
import com.labteto.dshmobile.uicontract.UiDocumentBoundValue
import com.labteto.dshmobile.uicontract.UiExecutionPhase
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
    private val workReads: com.labteto.dshmobile.uicontract.WorkReadUiPort,
    private val drafts: ConversationDraftUiPort,
    private val knownScenes: Set<UiSceneId>,
    private val navigationScenes: Set<UiSceneId>,
    private val navigate: (UiSceneId) -> Unit,
) : UiDocumentUiPort {
    private data class Context(val identity: UiSessionIdentity?, val workMode: Boolean, val messages: Set<String>,
        val phase: UiExecutionPhase, val queued: Int, val approvalTool: String?, val error: String?)
    private val contexts = state.state.map { synchronized(state.foregroundRunHandle.lock) {
        val snapshot = state.state.value
        val workMode = snapshot.usageMode == LocalUsageMode.WORK
        val chat = conversations.chat.snapshot()
        val work = conversations.work.snapshot()
        Context(state.uiBindingIdentity(snapshot), workMode, snapshot.messages.map { it.id }.toSet(),
            if (workMode) work.phase else chat.phase, if (workMode) work.queuedInputCount else chat.queuedInputCount,
            work.approval?.toolName.takeIf { workMode }, if (workMode) work.error else chat.error)
    } }.distinctUntilChanged()
    override val snapshots: StateFlow<UiDocumentsSceneSnapshot> = combine(contexts, store.revision, generation.generating) { context, _, busy ->
        try {
            val id = context.identity
            val records = id?.let { store.read(it.sessionId, context.messages, generation.budget, knownScenes) }.orEmpty()
            UiDocumentsSceneSnapshot(id?.sessionId, records.map { record ->
                val document = record.document
                val current = record.recoveryNotice == null && sourceMatches(document.source, id, context.workMode)
                UiDocumentSnapshot(document, record.provenance, document.actions.associate { action ->
                    val reason = when {
                        !current -> UiCommandRejection.STALE_IDENTITY
                        action is UiDocumentAction.AnswerApproval && conversations.work.snapshot().approval?.pendingId != action.pendingId -> UiCommandRejection.APPROVAL_EXPIRED
                        action is UiDocumentAction.OpenKnownScene && action.sceneId !in navigationScenes -> UiCommandRejection.UNSUPPORTED
                        else -> null
                    }
                    action.id to UiActionAvailability(reason == null, reason)
                }, if (!current) emptyMap() else mapOf(
                    UiDocumentBinding.EXECUTION_PHASE to UiDocumentBoundValue.Phase(context.phase),
                    UiDocumentBinding.QUEUED_INPUT_COUNT to UiDocumentBoundValue.Count(context.queued),
                    UiDocumentBinding.PENDING_APPROVAL_TOOL to (context.approvalTool?.let { UiDocumentBoundValue.Text(it) }
                        ?: UiDocumentBoundValue.Unavailable),
                    UiDocumentBinding.CURRENT_ERROR to (context.error?.let { UiDocumentBoundValue.Text(it) }
                        ?: UiDocumentBoundValue.Unavailable),
                ), record.recoveryNotice)
            }, id?.sessionId in busy, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UiDocumentsSceneSnapshot(context.identity?.sessionId, emptyList(), false, "ui_document_history_unavailable")
        }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(5_000), UiDocumentsSceneSnapshot(null, emptyList(), false, null))

    override fun snapshot() = snapshots.value
    override suspend fun generate(command: GenerateUiDocumentCommand) = generation.generate(command, knownScenes, navigationScenes)

    override suspend fun generateLocal(command: com.labteto.dshmobile.uicontract.GenerateLocalUiDocumentCommand): UiDocumentReceipt =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                synchronized(state.foregroundRunHandle.lock) {
                    val current = state.state.value
                    if (state.sessionTransitioning || state.uiBindingIdentity(current) != command.identity ||
                        (current.usageMode == LocalUsageMode.WORK) != command.workMode)
                        return@synchronized UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    val message = current.messages.firstOrNull { it.id == command.messageId }
                        ?: return@synchronized UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    val source = UiDocumentSource(command.identity.sessionId, message.id, command.identity.contextGeneration,
                        command.identity.profileId, command.identity.projectId, command.workMode)
                    val approval = conversations.work.snapshot().approval.takeIf { command.workMode }
                    val data = when (command.template) {
                        com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.STATUS -> com.labteto.dshmobile.uicontract.UiLocalDocumentData.Status(approval)
                        com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.MESSAGE -> com.labteto.dshmobile.uicontract.UiLocalDocumentData.ListItems(listOf(message.content))
                        com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.REPLY_FORM -> com.labteto.dshmobile.uicontract.UiLocalDocumentData.ReplyForm("")
                        com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_TOOL_ACTIVITY -> {
                            if (!command.workMode) return@synchronized UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                            // The existing Work Owner projects a bounded event-log view. This is a
                            // frozen snapshot, not a verified requirement or resumable tool action.
                            val tools = workReads.toolActivitiesForUi(source.sessionId).takeLast(8)
                            com.labteto.dshmobile.uicontract.UiLocalDocumentData.TableData(
                                title = "工具事件（制作时快照；完成不等于核验）",
                                headers = listOf("工具", "真实阶段", "错误码"),
                                rows = tools.map { activity ->
                                    listOf(activity.name, activity.phase.name, activity.errorCode.orEmpty())
                                },
                            )
                        }
                        com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.FACTS_TABLE -> {
                            // A point-in-time Owner snapshot; not an automatically updating task status.
                            val chatSnapshot = conversations.chat.snapshot()
                            val workSnapshot = conversations.work.snapshot()
                            val activePhase = if (command.workMode) workSnapshot.phase.name else chatSnapshot.phase.name
                            val activeStatus = if (command.workMode) workSnapshot.status.name else chatSnapshot.status.name
                            val activeQueued = if (command.workMode) workSnapshot.queuedInputCount else chatSnapshot.queuedInputCount
                            com.labteto.dshmobile.uicontract.UiLocalDocumentData.TableData(
                                title = "会话事实（制作时快照）",
                                headers = listOf("字段", "Owner 读取值"),
                                rows = listOf(
                                    listOf("会话状态", activeStatus),
                                    listOf("执行阶段", activePhase),
                                    listOf("排队输入", activeQueued.toString()),
                                ),
                            )
                        }
                    }
                    val previous = store.read(source.sessionId, setOf(source.messageId), generation.budget, knownScenes)
                        .firstOrNull { it.recoveryNotice == null && it.document.source == source }?.document
                    val result = com.labteto.dshmobile.uicontract.LocalUiDocumentBuilder.build(
                        previous?.documentId ?: java.util.UUID.randomUUID().toString(), source,
                        previous?.revision?.let { Math.addExact(it, 1) } ?: 0, data, generation.budget, knownScenes)
                    val document = result.getOrNull() ?: return@synchronized UiDocumentReceipt.Fallback(message.content, "ui_document_unavailable")
                    val definition = com.labteto.dshmobile.local.presentation.document.LocalUiDocumentCodec.encode(document)
                    com.labteto.dshmobile.local.presentation.document.LocalUiDocumentCodec.decode(definition.toString(), source,
                        generation.budget, knownScenes, approval?.pendingId).getOrThrow()
                    if (store.publish(document, com.labteto.dshmobile.uicontract.UiDocumentProvenance(null, "", false,
                        com.labteto.dshmobile.uicontract.UiDocumentOrigin.LOCAL), approval?.pendingId, previous, generation.budget, knownScenes))
                        UiDocumentReceipt.Published(document.documentId)
                    else UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { UiDocumentReceipt.Failed(failure.message) }
        }

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
    private val workReads: com.labteto.dshmobile.uicontract.WorkReadUiPort,
) {
    fun create(scope: CoroutineScope, drafts: ConversationDraftUiPort, knownScenes: Set<UiSceneId>,
        navigationScenes: Set<UiSceneId> = knownScenes,
        navigate: (UiSceneId) -> Unit): UiDocumentUiPort =
        LocalUiDocumentUiAdapter(scope, state, conversations, generation, store, workReads, drafts, knownScenes, navigationScenes, navigate)
}
