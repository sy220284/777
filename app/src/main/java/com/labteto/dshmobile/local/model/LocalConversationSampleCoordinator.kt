package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.recordForeground
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex

/**
 * A single, read-only model sample for the currently selected session.
 * A sample has no tool bindings, never appends a transcript message and cannot modify the Work goal.
 * It reuses the existing model admission, retry, session event and usage owners.
 */
internal data class LocalConversationSampleResult(
    val sessionId: String,
    val model: String,
    val content: String,
    val requestId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val usageReported: Boolean,
    val estimatedCostCny: Double?,
)

internal const val LOCAL_SESSION_SAMPLE_MAX_PROMPT_BYTES = 4_000
internal const val LOCAL_SESSION_SAMPLE_MAX_OUTPUT_BYTES = 65_536

internal fun validateLocalSessionSamplePrompt(prompt: String): String {
    val trimmed = prompt.trim()
    require(trimmed.isNotEmpty() &&
        trimmed.toByteArray(Charsets.UTF_8).size <= LOCAL_SESSION_SAMPLE_MAX_PROMPT_BYTES
    ) { "采样指令不能为空，且不能超过 4000 字节" }
    return trimmed
}

/** Copy only human conversation text. Tool records and source session events stay outside the fork. */
internal fun localSessionSampleMessages(
    history: List<LocalHarnessMessage>,
    prompt: String,
): List<JsonObject> {
    val boundedHistory = history.asSequence()
        .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
        .takeLastSampleContext(16)
        .map { buildJsonObject {
            put("role", it.role)
            put("content", it.content.take(6_000))
        } }
        .toList()
    return listOf(
        buildJsonObject {
            put("role", "system")
            put("content", "这是一次临时会话采样。依据提供的历史，模拟下一条助手回复；不执行任何工具，不宣称真实修改了文件，也不写回会话。只输出模拟文本。")
        },
    ) + boundedHistory + listOf(buildJsonObject {
        put("role", "user")
        put("content", validateLocalSessionSamplePrompt(prompt))
    })
}

private fun <T> Sequence<T>.takeLastSampleContext(limit: Int): Sequence<T> =
    toList().takeLast(limit).asSequence()
/**
 * Snapshot the model's actual, already-compacted conversation instead of the latest sixteen
 * display messages. The fork keeps completed tool-call pairs and active Work instructions.
 * No tool schema or writable state is supplied to the auxiliary inference request.
 */
internal fun localSessionSampleModelMessages(
    history: List<JsonObject>,
    prompt: String,
): List<JsonObject> {
    val instruction = validateLocalSessionSamplePrompt(prompt)
    require(history.isNotEmpty()) { "当前会话缺少可用的模型上下文" }
    val stable = durableModelHistorySnapshot(history)
    require(stable.any {
        (it["role"] as? JsonPrimitive)?.contentOrNull in setOf("user", "assistant")
    }) { "当前模型历史没有可采样的对话" }
    val guard = "这是对当前模型上下文进行的一次只读会话采样。模拟下一条助手回复；不要调用工具、修改任何文件或声称已完成外部操作。采样结果不会写入原会话。"
    val first = stable.first()
    val existingSystem = if ((first["role"] as? JsonPrimitive)?.contentOrNull == "system")
        (first["content"] as? JsonPrimitive)?.contentOrNull else null
    val fork = if (existingSystem != null) {
        listOf(JsonObject(first + ("content" to JsonPrimitive(existingSystem + "\n\n" + guard)))) +
            stable.drop(1)
    } else {
        listOf(buildJsonObject {
            put("role", "system")
            put("content", guard)
        }) + stable
    }
    return fork + buildJsonObject {
        put("role", "user")
        put("content", instruction)
    }
}


/** An edited message keeps its ID; freshness must compare the captured message bytes. */
internal fun localSessionSampleContextIsCurrent(
    sourceSessionId: String,
    sourceMessages: List<LocalHarnessMessage>,
    currentSessionId: String,
    currentMessages: List<LocalHarnessMessage>,
): Boolean = sourceSessionId == currentSessionId && sourceMessages == currentMessages

@Singleton
class LocalConversationSampleCoordinator @Inject internal constructor(
    private val stateStore: LocalRuntimeStateStore,
    private val modelGateway: LocalModelGateway,
    private val auxiliary: LocalAuxiliaryModelRequestRuntime,
    private val usageTracker: DeepSeekUsageTracker,
    private val storage: LocalSessionStorageRuntime,
) {
    private val sampleLock = Mutex()

    internal suspend fun sample(expectedSessionId: String, rawPrompt: String): LocalConversationSampleResult {
        val prompt = validateLocalSessionSamplePrompt(rawPrompt)
        check(sampleLock.tryLock()) { "另一项会话采样正在进行，请先取消或查看其结果" }
        try {
            val snapshot = stateStore.state.value
            check(expectedSessionId.isNotBlank() && snapshot.sessionId == expectedSessionId) {
                "会话已切换，请重新选择要采样的会话"
            }
            check(snapshot.modelState.configured) { "请先连接可用模型" }
            check(!snapshot.loading && !snapshot.kernel.running &&
                !stateStore.foregroundRunHandle.hasLiveJob() && !stateStore.sessionTransitioning
            ) { "请在当前会话空闲时采样" }
            val sourceMessages = snapshot.messages.toList()
            val lastMessageId = sourceMessages.lastOrNull()?.id
            check(lastMessageId != null) { "当前会话没有可供采样的历史" }
            val sourceModelHistory = synchronized(stateStore.foregroundRunHandle.lock) {
                check(stateStore.foregroundRunHandle.sessionId == expectedSessionId) {
                    "会话运行上下文已切换，请重新采样"
                }
                stateStore.foregroundRunHandle.modelHistory.snapshot()
            }
            val sampleMessages = localSessionSampleModelMessages(sourceModelHistory, prompt)
            val profile = modelGateway.profileForRoute(
                profileId = snapshot.modelState.modelSelection.activeProfileId,
                model = snapshot.modelState.model,
                baseUrl = snapshot.modelState.baseUrl,
            )
            val eventLog = storage.eventLogs.get(expectedSessionId)
            val reply = withTimeout(30_000L) {
                auxiliary.complete(
                    modelAttempts = snapshot.modelState.modelAttempts,
                    profile = profile,
                    messages = sampleMessages,
                    eventLog = eventLog,
                    operation = "session/sample",
                )
            }
            // The real request is accounted for even if the user switches sessions during the call.
            usageTracker.recordForeground(
                snapshot = snapshot,
                reply = reply,
                action = TokenUsageAction.SESSION_SAMPLE,
                turnId = snapshot.transcriptIndex.latestUserMessageId,
            )
            val output = reply.content.orEmpty()
            check(reply.toolCalls.isEmpty()) { "模型尝试调用工具，采样已拒绝" }
            check(output.isNotBlank() && output.toByteArray(Charsets.UTF_8).size <= LOCAL_SESSION_SAMPLE_MAX_OUTPUT_BYTES) {
                "采样结果为空或超过 64 KiB 限制"
            }
            val current = stateStore.state.value
            check(localSessionSampleContextIsCurrent(
                expectedSessionId, sourceMessages, current.sessionId, current.messages,
            ) && current.messages.lastOrNull()?.id == lastMessageId &&
                stateStore.foregroundRunHandle.sessionId == expectedSessionId &&
                stateStore.foregroundRunHandle.modelHistory.snapshot() == sourceModelHistory &&
                !current.kernel.running && !current.loading
            ) { "会话内容已变化，旧采样结果已失效" }
            eventLog.append("session/sample", buildJsonObject {
                put("status", "completed")
                put("request_id", reply.requestId)
                put("prompt_bytes", prompt.toByteArray(Charsets.UTF_8).size)
                put("input_tokens", reply.usage.promptTokens)
                put("output_tokens", reply.usage.completionTokens)
                put("usage_reported", reply.usage.reported)
                put("source_model_message_count", sourceModelHistory.size)
            })
            return LocalConversationSampleResult(
                sessionId = expectedSessionId,
                model = profile.model,
                content = output,
                requestId = reply.requestId,
                inputTokens = reply.usage.promptTokens,
                outputTokens = reply.usage.completionTokens,
                usageReported = reply.usage.reported,
                estimatedCostCny = reply.requestId.takeIf(String::isNotBlank)
                    ?.let(usageTracker::analyticsRecord)?.estimatedCostCny?.takeIf { it > 0.0 },
            )
        } finally {
            sampleLock.unlock()
        }
    }
}
