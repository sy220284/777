package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.model.buildLocalUserModelMessage
import com.labteto.dshmobile.local.model.durableModelHistorySnapshot
import com.labteto.dshmobile.local.restoreLocalModelHistory
import com.labteto.dshmobile.local.runtime.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.prepareLocalSend
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.LocalUserMessageEditResult
import com.labteto.dshmobile.local.session.buildLocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.session.decodeTranscriptMessages
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A Work edit rewrites the logical timeline; previously performed external side effects remain auditable. */
internal sealed interface LocalWorkMessageEditPreparation {
    data class Ready(val send: LocalPreparedSend) : LocalWorkMessageEditPreparation
    data class Rejected(val reason: LocalUserMessageEditResult) : LocalWorkMessageEditPreparation
}

private const val LEGACY_ATTACHMENT_MARKER = "本次附件已导入本机工作区："

/**
 * Work-owned historical editing transaction. The caller holds the foreground lock through
 * rewriting and starting the replacement, so no other send can enter between the two.
 *
 * Never replay tool calls from discarded history; the new Work run starts with one newly
 * admitted user instruction. Physical file changes and external tool side effects are not undone.
 */
@Singleton
internal class LocalWorkUserMessageEditor @Inject constructor(
    private val runtime: LocalRuntimeStateStore,
    private val storage: LocalSessionStorageRuntime,
    private val runs: LocalWorkRunRegistry,
) {
    internal fun rewrite(messageId: String, replacement: String): LocalWorkMessageEditPreparation {
        fun rejected(reason: LocalUserMessageEditResult) =
            LocalWorkMessageEditPreparation.Rejected(reason)
        val state = runtime.state.value
        if (state.usageMode != LocalUsageMode.WORK) return rejected(LocalUserMessageEditResult.WRONG_MODE)
        if (!state.modelState.configured) return rejected(LocalUserMessageEditResult.UNCONFIGURED)
        val handle = runtime.foregroundRunHandle
        if (
            state.loading || state.kernel.running || runtime.sessionTransitioning ||
            handle.hasLiveJob() || handle.pendingInputs.size() != 0 ||
            runs.live(state.sessionId) != null ||
            state.work.pendingApproval != null || state.work.pendingQuestion != null
        ) return rejected(LocalUserMessageEditResult.BUSY)

        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            state.sessionId, LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return rejected(LocalUserMessageEditResult.BUSY)
        try {
            val now = runtime.state.value
            if (now.sessionId != state.sessionId || now.usageMode != LocalUsageMode.WORK ||
                now.loading || now.kernel.running || runtime.sessionTransitioning ||
                handle.hasLiveJob() || handle.pendingInputs.size() != 0 ||
                runs.live(now.sessionId) != null
            ) return rejected(LocalUserMessageEditResult.BUSY)

            val log = storage.eventLogs.get(now.sessionId)
            val transcript = LocalSessionTranscriptPager(log).all()
            val index = transcript.indexOfFirst { it.id == messageId && it.role == "user" }
            if (index < 0) return rejected(LocalUserMessageEditResult.MESSAGE_MISSING)
            val original = transcript[index]
            val updatedText = replacement.trim()
            val oldText = userEditableText(original)
            if (oldText.trim() == updatedText) return rejected(LocalUserMessageEditResult.UNCHANGED)
            if (original.blocks.any { it is LocalMessageBlock.Unknown }) {
                return rejected(LocalUserMessageEditResult.HISTORY_UNAVAILABLE)
            }
            val attachments = original.blocks.mapNotNull { block ->
                when (block) {
                    is LocalMessageBlock.Image -> LocalImportedAttachment(
                        block.name, block.relativePath, block.mediaType, block.bytes,
                        block.attachmentId, block.width, block.height,
                    )
                    is LocalMessageBlock.File -> LocalImportedAttachment(
                        block.name, block.relativePath, block.mediaType, block.bytes, block.attachmentId,
                    )
                    else -> null
                }
            }
            var prepared = prepareLocalSend(updatedText, attachments)
            if (original.blocks.isEmpty() && LEGACY_ATTACHMENT_MARKER in original.content) {
                val suffix = original.content.substring(original.content.indexOf(LEGACY_ATTACHMENT_MARKER))
                val executionText = listOf(updatedText, suffix).filter(String::isNotBlank).joinToString("\n\n")
                prepared = LocalPreparedSend(
                    content = executionText,
                    visibleContent = updatedText,
                    memoryInput = updatedText,
                    modelMessage = buildLocalUserModelMessage(executionText, emptyList()),
                )
            }
            if (prepared == null) return rejected(LocalUserMessageEditResult.EMPTY)

            val source = log.latestMatching(setOf("user/message", LOCAL_AGENT_INBOX_EVENT_TYPE)) { data ->
                decodeTranscriptMessages(data).orEmpty().any { it.id == messageId }
            } ?: return rejected(LocalUserMessageEditResult.HISTORY_UNAVAILABLE)
            val earlierEvents = log.withEvents { all ->
                all.takeWhile { it.sequence < source.sequence }.toList()
            }
            val history = restoreLocalModelHistory(
                earlierEvents, emptyList(), ModelHistoryCheckpointCodec(),
            ).messages
            if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull != "system") {
                return rejected(LocalUserMessageEditResult.HISTORY_UNAVAILABLE)
            }
            val prior = transcript.take(index)
            val controls = projectWorkSessionControls(LocalWorkState(), earlierEvents)
            val event = log.append("work/active-transcript", buildJsonObject {
                put("reason", "user-edited")
                put("transcript", encodeTranscriptMessages(prior))
                put("model_history", JsonArray(history))
                put("plan", JsonArray(controls.plan.map(::JsonPrimitive)))
                put("todos", JsonArray(controls.todos.map { item ->
                    buildJsonObject {
                        put("content", item.content)
                        put("status", item.status)
                    }
                }))
                put("goal", controls.goal?.let { goal ->
                    buildJsonObject {
                        put("description", goal.description)
                        put("status", goal.status)
                        goal.note?.let { put("note", it) }
                    }
                } ?: JsonNull)
                put("plan_mode", controls.planMode)
            })
            handle.modelHistory.reset(history)
            val resources = runtime.resourceSnapshot()
            runtime.projection.update { current ->
                if (current.sessionId != now.sessionId || current.usageMode != LocalUsageMode.WORK) current
                else current.copy(
                    messages = prior.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
                    transcriptIndex = buildLocalTranscriptRuntimeIndex(prior),
                    work = current.work.copy(
                        plan = controls.plan,
                        todos = controls.todos,
                        goal = controls.goal,
                        planMode = controls.planMode,
                        team = LocalAgentTeamUiState(),
                        workflowProgress = null,
                        pendingApproval = null,
                        pendingQuestion = null,
                    ),
                    kernel = current.kernel.copy(
                        contextChars = handle.modelHistory.encodedChars,
                        contextBudgetChars = runtime.contextBudgetCharsFor(current, resources),
                    ),
                    error = null,
                )
            }
            handle.transcriptProjectionCursor = maxOf(handle.transcriptProjectionCursor ?: -1L, event.sequence)
            log.append(ModelHistoryCheckpointCodec.EVENT_TYPE, ModelHistoryCheckpointCodec().encode(
                messages = durableModelHistorySnapshot(history),
                reason = "work/user-edited",
                asOfSequence = log.latestSequence(),
            ))
            // The event and checkpoint are durable even if the optional materialized snapshot lags.
            if (!storage.enqueueCurrentSnapshot(now.sessionId)) {
                runtime.projection.publishError("工作历史已重写，快照保存排队失败；将继续从持久事件恢复")
            }
            return LocalWorkMessageEditPreparation.Ready(prepared)
        } finally {
            lease.close()
        }
    }
}

internal fun userEditableText(message: LocalHarnessMessage): String {
    if (message.blocks.isNotEmpty()) {
        return message.blocks.filterIsInstance<LocalMessageBlock.Text>()
            .joinToString("\n") { it.text }
    }
    val index = message.content.indexOf(LEGACY_ATTACHMENT_MARKER)
    return if (index < 0) message.content else message.content.substring(0, index).trimEnd()
}
