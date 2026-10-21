package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalConversationUiPortComposition
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalUiDocumentGeneration
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalUiDocumentSessionStore
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiDocumentUiPort
import com.labteto.dshmobile.uicontract.DeleteUiDocumentCommand
import com.labteto.dshmobile.uicontract.UiCommandReceipt
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
import com.labteto.dshmobile.uicontract.preparedInput
import com.labteto.dshmobile.uicontract.invalidInputFields
import com.labteto.dshmobile.uicontract.withUserEditedText
import com.labteto.dshmobile.uicontract.withUserEditedTableCell
import com.labteto.dshmobile.uicontract.withUserEditedChartPoint
import com.labteto.dshmobile.local.uiBindingIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

/**
 * Shared foreground fence for an explicit document deletion. The first check protects
 * preflight; the SAME check must run again under the foreground lock immediately
 * before the durable tombstone, after any slow EventLog read has completed.
 */
internal fun mayDeleteUiDocumentForForeground(
    command: DeleteUiDocumentCommand,
    currentIdentity: UiSessionIdentity?,
    workMode: Boolean,
    transitioning: Boolean,
    visibleMessageIds: Set<String>,
): Boolean = !transitioning && currentIdentity == command.identity &&
    command.source.sessionId == command.identity.sessionId &&
    command.source.contextGeneration == command.identity.contextGeneration &&
    command.source.profileId == command.identity.profileId &&
    command.source.projectId == command.identity.projectId &&
    command.source.workMode == workMode &&
    command.source.messageId in visibleMessageIds

/** Host adapter dispatches only closed intentions; it never executes arbitrary model actions. */
internal class LocalUiDocumentUiAdapter(
    scope: CoroutineScope,
    private val state: LocalRuntimeStateStore,
    private val conversations: LocalConversationUiPortComposition,
    private val generation: LocalUiDocumentGeneration,
    private val store: LocalUiDocumentSessionStore,
    private val workReads: com.labteto.dshmobile.uicontract.WorkReadUiPort,
    private val media: com.labteto.dshmobile.uicontract.MediaUiPort,
    private val drafts: ConversationDraftUiPort,
    private val knownScenes: Set<UiSceneId>,
    private val navigationScenes: Set<UiSceneId>,
    private val navigate: (UiSceneId) -> Unit,
) : UiDocumentUiPort {
    private data class Context(val identity: UiSessionIdentity?, val workMode: Boolean, val messages: Set<String>,
        val phase: UiExecutionPhase, val queued: Int, val approvalTool: String?, val error: String?)
    private val contexts = state.state.map { synchronized(state.foregroundRunHandle.lock) {
        val current = state.state.value
        val facts = conversations.documentFactsSnapshot()
        // Only document facts and visible message IDs are required. Do not project
        // every chat/work message when a status or approval field changes.
        Context(facts.identity, facts.workMode, current.messages.map { it.id }.toSet(),
            facts.phase, facts.queuedInputCount, facts.approval?.toolName, facts.error)
    } }.distinctUntilChanged()
    override val snapshots: StateFlow<UiDocumentsSceneSnapshot> = combine(contexts, store.revision, generation.generating) { context, _, busy ->
        try {
            val id = context.identity
            val records = id?.let { store.read(it.sessionId, context.messages, generation.budget, knownScenes) }.orEmpty()
            UiDocumentsSceneSnapshot(id?.sessionId, records.map { record ->
                val document = record.document
                val readOnlyWorkProgress = record.provenance.origin ==
                    com.labteto.dshmobile.uicontract.UiDocumentOrigin.LOCAL &&
                    document.documentId == "work-live:${document.source.messageId}" &&
                    document.actions.isEmpty() && document.source.workMode &&
                    context.workMode && id?.sessionId == document.source.sessionId
                // Work activity cards are per-turn durable facts, never bindings to the
                // currently foreground turn. This also fences legacy saved BoundText nodes.
                // A detached Work card has no actions and cannot grant expired permissions.
                val current = record.recoveryNotice == null &&
                    (sourceMatches(document.source, id, context.workMode) || readOnlyWorkProgress)
                UiDocumentSnapshot(document, record.provenance, document.actions.associate { action ->
                    val reason = when {
                        !current -> UiCommandRejection.STALE_IDENTITY
                        action is UiDocumentAction.AnswerApproval && conversations.documentFactsSnapshot().approval?.pendingId != action.pendingId -> UiCommandRejection.APPROVAL_EXPIRED
                        action is UiDocumentAction.OpenKnownScene && action.sceneId !in navigationScenes -> UiCommandRejection.UNSUPPORTED
                        else -> null
                    }
                    action.id to UiActionAvailability(reason == null, reason)
                }, if (!current || readOnlyWorkProgress) emptyMap() else mapOf(
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

    override suspend fun documentsForMessages(
        identity: UiSessionIdentity, messageIds: Set<String>,
    ): List<UiDocumentSnapshot> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext emptyList()
        val admitted = synchronized(state.foregroundRunHandle.lock) {
            val current = state.state.value
            !state.sessionTransitioning && state.uiBindingIdentity(current) == identity
        }
        if (!admitted) return@withContext emptyList()
        val records = messageIds.chunked(120).flatMap { batch ->
            store.read(identity.sessionId, batch.toSet(), generation.budget, knownScenes)
        }
        // Historical cards contain durable text/artifact snapshots. Only the active
        // Owner can issue actions; a cold-page read intentionally grants no permissions.
        records.map { record ->
            UiDocumentSnapshot(record.document, record.provenance, emptyMap(), emptyMap(),
                record.recoveryNotice)
        }
    }
    override suspend fun generate(command: GenerateUiDocumentCommand) = generation.generate(command, knownScenes, navigationScenes)

    override suspend fun generateLocal(command: com.labteto.dshmobile.uicontract.GenerateLocalUiDocumentCommand): UiDocumentReceipt =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                // Capture just the foreground identity and message under the shared lock.
                // Work EventLog queries, document decoding and layout assembly can do disk IO:
                // they must not keep session switching or message sending blocked.
                val (source, messageText, approval) = synchronized(state.foregroundRunHandle.lock) {
                    val current = state.state.value
                    if (state.sessionTransitioning || state.uiBindingIdentity(current) != command.identity ||
                        (current.usageMode == LocalUsageMode.WORK) != command.workMode)
                        return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    val message = current.messages.firstOrNull { it.id == command.messageId }
                        ?: return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    Triple(
                        UiDocumentSource(command.identity.sessionId, message.id, command.identity.contextGeneration,
                            command.identity.profileId, command.identity.projectId, command.workMode),
                        message.content,
                        conversations.documentFactsSnapshot().approval.takeIf { command.workMode },
                    )
                }
                val data = when (command.template) {
                    com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.STATUS ->
                        com.labteto.dshmobile.uicontract.UiLocalDocumentData.Status(approval)
                    com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.MESSAGE ->
                        com.labteto.dshmobile.uicontract.UiLocalDocumentData.ListItems(listOf(messageText))
                    com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.REPLY_FORM ->
                        com.labteto.dshmobile.uicontract.UiLocalDocumentData.ReplyForm("")
                    com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_TOOL_ACTIVITY -> {
                        if (!command.workMode) return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                        val activities = workReads.toolActivitiesForUi(source.sessionId).takeLast(8)
                        com.labteto.dshmobile.uicontract.UiLocalDocumentData.TableData(
                            title = "工具事件（制作时快照；完成不等于核验）",
                            headers = listOf("工具", "真实阶段", "错误码"),
                            rows = activities.map { listOf(it.name, it.phase.name, it.errorCode.orEmpty()) },
                        )
                    }
                    com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.FACTS_TABLE -> {
                        val facts = conversations.documentFactsSnapshot()
                        com.labteto.dshmobile.uicontract.UiLocalDocumentData.TableData(
                            title = "会话事实（制作时快照）",
                            headers = listOf("字段", "Owner 读取值"),
                            rows = listOf(
                                listOf("会话状态", facts.status.name),
                                listOf("执行阶段", facts.phase.name),
                                listOf("排队输入", facts.queuedInputCount.toString()),
                            ),
                        )
                    }
                    com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.MEDIA_RESULTS -> {
                        // Owner indexes actual saved results for this specific session, not arbitrary model URLs.
                        val page = media.observe(source.sessionId, media.pageSize).first()
                        val assets = buildList {
                            page.results.take(12).forEach { result ->
                                val kind = when (result.kind.uppercase()) {
                                    "IMAGE" -> com.labteto.dshmobile.uicontract.UiDocumentMediaKind.IMAGE
                                    "VIDEO" -> com.labteto.dshmobile.uicontract.UiDocumentMediaKind.VIDEO
                                    "SPEECH" -> com.labteto.dshmobile.uicontract.UiDocumentMediaKind.AUDIO
                                    else -> null
                                }
                                if (kind != null && result.sessionId == source.sessionId &&
                                    media.assetForSession(result.id, source.sessionId) != null) {
                                    add(com.labteto.dshmobile.uicontract.UiDocumentNode.Media(
                                        "media_${result.id}", result.id, kind,
                                        result.description?.take(150)?.ifBlank { result.kind } ?: result.kind))
                                }
                            }
                            page.videos.take(8).forEach { video ->
                                if (video.sessionId == source.sessionId &&
                                    media.assetForSession(video.id, source.sessionId) != null)
                                    add(com.labteto.dshmobile.uicontract.UiDocumentNode.Media(
                                        "video_${video.id}", video.id,
                                        com.labteto.dshmobile.uicontract.UiDocumentMediaKind.VIDEO,
                                        video.description?.take(150)?.ifBlank { "视频" } ?: "视频"))
                            }
                        }
                        com.labteto.dshmobile.uicontract.UiLocalDocumentData.MediaResults(assets)
                    }
                    com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_TOOL_COUNTS -> {
                        if (!command.workMode)
                            return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                        // These counts reflect only the Owner-returned tool activity window.
                        // Tool calls are not claimed to have passed, finished or been verified.
                        val counts = workReads.toolActivitiesForUi(source.sessionId)
                            .groupingBy { it.name.take(100).ifBlank { "未命名工具" } }.eachCount()
                            .toList()
                            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                            .take(12)
                        if (counts.isEmpty()) {
                            com.labteto.dshmobile.uicontract.UiLocalDocumentData.ListItems(
                                listOf("当前会话暂无可展示的工具调用事件。"))
                        } else {
                            com.labteto.dshmobile.uicontract.UiLocalDocumentData.ChartData(
                                "工具调用次数（当前 Owner 返回窗口，未经核验）", "次",
                                counts.map { (name, count) ->
                                    com.labteto.dshmobile.uicontract.UiChartPoint(name, count.toDouble())
                                },
                            )
                        }
                    }
                    com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_EVIDENCE -> {
                        if (!command.workMode)
                            return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                        com.labteto.dshmobile.uicontract.UiLocalDocumentData.EvidenceLinks(
                            workReads.requirementEvidenceForUi(source.sessionId))
                    }
                    com.labteto.dshmobile.uicontract.UiLocalDocumentTemplate.WORK_TOOL_RESULT -> {
                        if (!command.workMode) return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                        val activity = workReads.toolActivitiesForUi(source.sessionId)
                            .filter { it.finishedSequence != null }.maxByOrNull { it.finishedSequence!! }
                            ?: return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                        val evidence = workReads.toolEvidenceForUi(source.sessionId, activity.callId, activity.finishedSequence!!)
                            ?: return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                        com.labteto.dshmobile.uicontract.UiLocalDocumentData.StructuredResult(
                            "${activity.name} · ${activity.phase.name} · 事件 ${activity.finishedSequence} · ${activity.callId} · 返回数据快照（未核验）", evidence,
                        )
                    }
                }
                // Each local template has a stable independent document identity.
                // Never replace the Work progress card or another local/model card merely
                // because they share the same message and Session context.
                val localDocId = "local-template:" + java.util.UUID.nameUUIDFromBytes(
                    "${command.template}:${source}".toByteArray(Charsets.UTF_8)).toString()
                val previous = store.read(source.sessionId, setOf(source.messageId), generation.budget, knownScenes)
                    .firstOrNull { it.recoveryNotice == null &&
                        it.document.documentId == localDocId && it.document.source == source }?.document
                val built = com.labteto.dshmobile.uicontract.LocalUiDocumentBuilder.build(
                    localDocId, source,
                    previous?.revision?.let { Math.addExact(it, 1L) } ?: 0L, data, generation.budget, knownScenes)
                val document = built.getOrNull()
                    ?: return@withContext UiDocumentReceipt.Fallback(messageText, "ui_document_unavailable")
                val definition = com.labteto.dshmobile.local.presentation.document.LocalUiDocumentCodec.encode(document)
                com.labteto.dshmobile.local.presentation.document.LocalUiDocumentCodec.decode(
                    definition.toString(), source, generation.budget, knownScenes, approval?.pendingId).getOrThrow()

                // The slow preparation may outlive the originating screen or message.
                // Revalidate the foreground under the same lock as the final Owner write;
                // the Session store independently CAS-checks the document revision.
                synchronized(state.foregroundRunHandle.lock) {
                    val current = state.state.value
                    if (state.sessionTransitioning || state.uiBindingIdentity(current) != command.identity ||
                        (current.usageMode == LocalUsageMode.WORK) != command.workMode ||
                        current.messages.none { it.id == source.messageId && it.content == messageText }
                    ) return@synchronized UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    if (store.publish(document, com.labteto.dshmobile.uicontract.UiDocumentProvenance(
                            null, "", false, com.labteto.dshmobile.uicontract.UiDocumentOrigin.LOCAL),
                            approval?.pendingId, previous, generation.budget, knownScenes))
                        UiDocumentReceipt.Published(document.documentId)
                    else UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { UiDocumentReceipt.Failed(failure.message) }
        }

    /**
     * All user corrections share exactly one versioned Session Owner transaction:
     * exact source lookup, immutable rewrite, active binding check, then EventLog CAS.
     * UI commands never authorize tools or edit Work-generated live/approval cards.
     */
    private suspend fun replaceDisplayNode(
        identity: UiSessionIdentity, documentId: String, revision: Long,
        source: UiDocumentSource,
        rewrite: (com.labteto.dshmobile.uicontract.UiDocument) ->
            com.labteto.dshmobile.uicontract.UiDocument?,
    ): UiDocumentReceipt = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (documentId.isBlank() || documentId.length > 200 ||
            source.sessionId != identity.sessionId || source.messageId.isBlank())
            return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
        try {
            val original = store.latestDocument(identity.sessionId, source.messageId,
                documentId, generation.budget, knownScenes)
                ?: return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            val doc = original.document
            if (original.recoveryNotice != null || doc.revision != revision ||
                doc.source != source ||
                doc.actions.any { it is UiDocumentAction.AnswerApproval } ||
                doc.documentId.startsWith("work-live:"))
                return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            val replacement = rewrite(doc)
                ?: return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            synchronized(state.foregroundRunHandle.lock) {
                val current = state.state.value
                if (state.sessionTransitioning ||
                    state.uiBindingIdentity(current) != identity ||
                    (current.usageMode == LocalUsageMode.WORK) != source.workMode ||
                    current.messages.none { it.id == source.messageId } ||
                    !sourceMatches(source, identity, current.usageMode == LocalUsageMode.WORK))
                    return@synchronized UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                val provenance = com.labteto.dshmobile.uicontract.UiDocumentProvenance(
                    null, "user-edited", false,
                    com.labteto.dshmobile.uicontract.UiDocumentOrigin.LOCAL)
                if (store.publish(replacement, provenance, null, doc,
                        generation.budget, knownScenes))
                    UiDocumentReceipt.Published(replacement.documentId)
                else UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiDocumentReceipt.Failed(failure.message) }
    }

    override suspend fun replaceText(
        command: com.labteto.dshmobile.uicontract.EditUiDocumentTextCommand,
    ): UiDocumentReceipt {
        if (command.textNodeId.isBlank() || command.replacementText.length > 16_384)
            return UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
        return replaceDisplayNode(command.identity, command.documentId, command.revision,
            command.source) { doc ->
            doc.withUserEditedText(command.textNodeId, command.replacementText)
        }
    }

    override suspend fun replaceTableCell(
        command: com.labteto.dshmobile.uicontract.EditUiDocumentTableCellCommand,
    ): UiDocumentReceipt {
        if (command.tableNodeId.isBlank() || command.rowIndex < 0 ||
            command.columnIndex < 0 || command.replacementText.length > 4096)
            return UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
        return replaceDisplayNode(command.identity, command.documentId, command.revision,
            command.source) { doc ->
            doc.withUserEditedTableCell(command.tableNodeId, command.rowIndex,
                command.columnIndex, command.replacementText)
        }
    }

    override suspend fun replaceChartPoint(
        command: com.labteto.dshmobile.uicontract.EditUiDocumentChartPointCommand,
    ): UiDocumentReceipt {
        if (command.chartNodeId.isBlank() || command.pointIndex < 0 ||
            !command.value.isFinite() || command.value !in 0.0..1.0e12)
            return UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
        return replaceDisplayNode(command.identity, command.documentId, command.revision,
            command.source) { doc ->
            doc.withUserEditedChartPoint(command.chartNodeId, command.pointIndex, command.value)
        }
    }

    /** Explicit user deletion, not a model-generated action; the EventLog owns the tombstone. */
    override suspend fun delete(command: DeleteUiDocumentCommand): UiDocumentReceipt =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val eligible = synchronized(state.foregroundRunHandle.lock) {
                val current = state.state.value
                mayDeleteUiDocumentForForeground(
                    command, state.uiBindingIdentity(current),
                    current.usageMode == LocalUsageMode.WORK, state.sessionTransitioning,
                    current.messages.map { it.id }.toSet(),
                )
            }
            if (!eligible) return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            try {
                val record = store.latestDocument(command.source.sessionId, command.source.messageId,
                    command.documentId, generation.budget, knownScenes)
                    ?: return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                if (record.recoveryNotice != null || record.document.revision != command.revision ||
                    record.document.source != command.source)
                    return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                // Reading the latest revision can race navigation, mode changes or
                // Session rebinding. Never commit the tombstone on the old snapshot.
                synchronized(state.foregroundRunHandle.lock) {
                    val current = state.state.value
                    if (!mayDeleteUiDocumentForForeground(
                            command, state.uiBindingIdentity(current),
                            current.usageMode == LocalUsageMode.WORK, state.sessionTransitioning,
                            current.messages.map { it.id }.toSet(),
                        ))
                        UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    else if (store.delete(record.document, generation.budget, knownScenes))
                        UiDocumentReceipt.Deleted
                    else UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { UiDocumentReceipt.Failed(failure.message) }
        }

    override suspend fun deleteBatch(commands: List<DeleteUiDocumentCommand>): UiDocumentReceipt =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (commands.size !in 2..32 || commands.map { it.source.messageId to it.documentId }
                    .distinct().size != commands.size)
                return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            val identity = commands.first().identity
            if (commands.any { it.identity != identity || it.source.sessionId != identity.sessionId })
                return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            try {
                val documents = commands.map { command ->
                    val record = store.latestDocument(identity.sessionId, command.source.messageId,
                        command.documentId, generation.budget, knownScenes)
                        ?: return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    if (record.recoveryNotice != null ||
                        record.document.revision != command.revision ||
                        record.document.source != command.source)
                        return@withContext UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    record.document
                }
                synchronized(state.foregroundRunHandle.lock) {
                    val current = state.state.value
                    if (state.sessionTransitioning ||
                        state.uiBindingIdentity(current) != identity ||
                        documents.any { doc ->
                            (current.usageMode == LocalUsageMode.WORK) != doc.source.workMode ||
                            current.messages.none { it.id == doc.source.messageId } ||
                            !sourceMatches(doc.source, identity, doc.source.workMode)
                        }) UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                    else if (store.deleteBatch(documents, generation.budget, knownScenes))
                        UiDocumentReceipt.Deleted
                    else UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { UiDocumentReceipt.Failed(failure.message) }
        }

    override fun submit(identity: UiSessionIdentity, request: UiDocumentActionRequest): UiDocumentReceipt {
        if (request.source.sessionId != identity.sessionId || request.source.messageId.isBlank() ||
            request.documentId.isBlank() || request.documentId.length > 200)
            return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        // The Session EventLog may need disk IO. Do not hold the foreground run
        // handle lock during the indexed exact-card lookup.
        val authoritative = try {
            store.latestDocument(identity.sessionId, request.source.messageId,
                request.documentId, generation.budget, knownScenes)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return UiDocumentReceipt.Failed(failure.message) }
            ?: return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        return synchronized(state.foregroundRunHandle.lock) {
            if (conversations.identitySnapshot() != identity || state.sessionTransitioning)
                return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            val current = state.state.value
            val workMode = current.usageMode == LocalUsageMode.WORK
            // Retain the old active-transcript fence and recheck identity AFTER
            // the IO: expired or historical cards cannot dispatch privileged actions.
            if (current.messages.none { it.id == request.source.messageId })
                return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            if (authoritative.recoveryNotice != null) return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            val record = authoritative.document
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
                    val text = record.preparedInput(action, fields)
                    when {
                        text.isBlank() -> UiDocumentReceipt.Rejected(UiCommandRejection.EMPTY_INPUT)
                        action.submit && !request.confirmed -> UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
                        action.submit -> UiDocumentReceipt.OwnerReceipt(if (workMode)
                            conversations.work.submit(WorkConversationUiCommand.SendText(identity, text))
                            else conversations.chat.submit(com.labteto.dshmobile.uicontract.ChatUiCommand.SendText(identity, text)))
                        else -> { drafts.updateDraft(identity.sessionId, text); UiDocumentReceipt.DraftPrepared }
                    }
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
    private val media: com.labteto.dshmobile.uicontract.MediaUiPort,
) {
    fun create(scope: CoroutineScope, drafts: ConversationDraftUiPort, knownScenes: Set<UiSceneId>,
        navigationScenes: Set<UiSceneId> = knownScenes,
        navigate: (UiSceneId) -> Unit): UiDocumentUiPort =
        LocalUiDocumentUiAdapter(scope, state, conversations, generation, store, workReads, media, drafts, knownScenes, navigationScenes, navigate)
}
