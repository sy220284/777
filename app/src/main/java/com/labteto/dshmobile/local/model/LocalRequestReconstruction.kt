package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal enum class LocalRequestReconstructionStatus {
    VERIFIED,
    REDACTED_VERIFIED,
    EVIDENCE_ONLY,
    INVALID,
}

internal data class LocalProviderAttemptReconstruction(
    val eventSequence: Long,
    val attempt: Int,
    val recoveryRound: Int,
    val model: String?,
    val baseUrl: String?,
    val profileId: String?,
    val provider: String?,
    val protocol: String?,
    val streaming: Boolean,
    val temperature: Double?,
    val promptCacheComparisonResponseId: String?,
    val promptCacheKey: String?,
    val promptCacheTtl: String?,
    val messages: List<JsonObject>?,
    val tools: JsonArray?,
    val contextMessages: JsonArray?,
    val messageDigest: String?,
    val contextDigest: String?,
    val toolSchemaDigest: String?,
    val fingerprintVerified: Boolean,
    val redacted: Boolean,
    val issues: List<String>,
)

internal data class LocalRequestReconstruction(
    val requestUid: String,
    val headerSequence: Long,
    val version: Int,
    val status: LocalRequestReconstructionStatus,
    val messages: List<JsonObject>?,
    val tools: JsonArray?,
    val contextMessages: JsonArray?,
    val originalMessageDigest: String?,
    val reconstructedMessageDigest: String?,
    val originalContextDigest: String?,
    val reconstructedContextDigest: String?,
    val toolSchemaDigest: String?,
    val envelopeFingerprintVerified: Boolean,
    val exactMessageDigestVerified: Boolean?,
    val exactContextDigestVerified: Boolean?,
    val model: String?,
    val baseUrl: String?,
    val profileId: String?,
    val provider: String?,
    val protocol: String?,
    val temperature: Double?,
    val providerAttempts: List<LocalProviderAttemptReconstruction>,
    val issues: List<String>,
)

internal fun reconstructLocalModelRequest(
    eventLog: LocalSessionEventLog,
    requestUid: String,
): LocalRequestReconstruction? {
    require(requestUid.isNotBlank()) { "requestUid 不能为空" }
    val header = eventLog.latestMatching(setOf(REQUEST_HEADER_EVENT)) { data ->
        data["request_uid"]?.jsonPrimitive?.contentOrNull == requestUid
    } ?: return null
    val eventAt: (Long) -> LocalSessionEventLog.Event? = { sequence ->
        eventAtSequence(eventLog, sequence)
    }
    val base = reconstructLocalModelRequest(
        header = header,
        eventAt = eventAt,
    )
    val attempts = eventLog.withEvents { events ->
        events.filter { event ->
            event.sequence > header.sequence &&
                event.type == REQUEST_PROVIDER_ATTEMPT_EVENT &&
                event.data["request_uid"]?.jsonPrimitive?.contentOrNull == requestUid
        }.map { event ->
            reconstructProviderAttempt(
                event = event,
                header = header,
                eventAt = eventAt,
            )
        }.toList()
    }
    val attemptIssues = attempts.flatMap { reconstructedAttempt ->
        reconstructedAttempt.issues.map { issue ->
            "provider-attempt seq=" + reconstructedAttempt.eventSequence + ": " + issue
        }
    }
    return if (attemptIssues.isEmpty()) {
        base.copy(providerAttempts = attempts)
    } else {
        base.copy(
            status = LocalRequestReconstructionStatus.INVALID,
            providerAttempts = attempts,
            issues = base.issues + attemptIssues,
        )
    }
}

internal fun reconstructLocalModelRequest(
    header: LocalSessionEventLog.Event,
    eventAt: (Long) -> LocalSessionEventLog.Event?,
): LocalRequestReconstruction {
    require(header.type == REQUEST_HEADER_EVENT) { "请求重建必须从 request/header 开始" }
    val data = header.data
    val requestUid = data.string("request_uid") ?: ""
    val version = data["version"]?.jsonPrimitive?.intOrNull ?: 1
    val issues = mutableListOf<String>()

    val originalMessageDigest = data.string("message_digest")
    val originalContextDigest =
        data.string("context_digest")
            ?: if (version <= 1) data.string("context_surface_digest") else null
    val expectedToolDigest = data.string("tool_schema_digest")
    val expectedMessageSurfaceDigest = data.string("message_surface_digest")
    val expectedContextSurfaceDigest = data.string("context_surface_digest")
    val routeFingerprint = data.string("route_fingerprint")
    val expectedEnvelope = data.string("request_envelope_fingerprint")

    fun surface(sequenceKey: String, expectedType: String): LocalSessionEventLog.Event? {
        val sequence = data[sequenceKey]?.jsonPrimitive?.longOrNull ?: return null
        if (sequence >= header.sequence) {
            issues += "$sequenceKey 必须指向 request/header 之前的 Surface：$sequence >= ${header.sequence}"
            return null
        }
        val event = eventAt(sequence)
        if (event == null) {
            issues += "$sequenceKey 指向不存在的事件：$sequence"
            return null
        }
        if (event.type != expectedType) {
            issues += "$sequenceKey 类型不匹配：期望 $expectedType，实际 ${event.type}"
            return null
        }
        return event
    }

    val messageSurface = surface("message_surface_seq", REQUEST_MESSAGE_SURFACE_EVENT)
    val toolSurface = surface("tool_surface_seq", REQUEST_TOOL_SURFACE_EVENT)
    val contextSurface = surface("context_surface_seq", REQUEST_CONTEXT_SURFACE_EVENT)

    if (data["tool_surface_seq"]?.jsonPrimitive?.longOrNull == null) {
        issues += "request/header 缺少 tool_surface_seq"
    }
    if (data["context_surface_seq"]?.jsonPrimitive?.longOrNull == null) {
        issues += "request/header 缺少 context_surface_seq"
    }
    if (version >= 2) {
        if (data["message_surface_seq"]?.jsonPrimitive?.longOrNull == null) {
            issues += "V2 request/header 缺少 message_surface_seq"
        }
        if (expectedMessageSurfaceDigest == null) {
            issues += "V2 request/header 缺少 message_surface_digest"
        }
        if (originalContextDigest == null) {
            issues += "V2 request/header 缺少 context_digest"
        }
        if (expectedContextSurfaceDigest == null) {
            issues += "V2 request/header 缺少 context_surface_digest"
        }
    }

    val messages = messageSurface?.data?.get("messages").asObjectListOrNull().also {
        if (messageSurface != null && it == null) {
            issues += "request/message-surface 缺少合法 messages"
        }
    }
    val tools = toolSurface?.data?.get("schemas") as? JsonArray
        ?: if (toolSurface != null) {
            issues += "request/tool-surface 缺少合法 schemas"
            null
        } else null
    val contextMessages = contextSurface?.data?.get("messages") as? JsonArray
        ?: if (contextSurface != null) {
            issues += "request/context-surface 缺少合法 messages"
            null
        } else null

    val reconstructedMessageDigest = messages?.let { stableJsonSha256(JsonArray(it)) }
    val reconstructedToolDigest = tools?.let(::stableJsonSha256)
    val reconstructedContextDigest = contextMessages?.let(::stableJsonSha256)

    verifySurfaceDigest(
        name = "message",
        event = messageSurface,
        expectedFromHeader = expectedMessageSurfaceDigest,
        reconstructed = reconstructedMessageDigest,
        issues = issues,
    )
    verifySurfaceDigest(
        name = "tool",
        event = toolSurface,
        expectedFromHeader = expectedToolDigest,
        reconstructed = reconstructedToolDigest,
        issues = issues,
    )
    verifySurfaceDigest(
        name = "context",
        event = contextSurface,
        expectedFromHeader = expectedContextSurfaceDigest,
        reconstructed = reconstructedContextDigest,
        issues = issues,
    )

    val messageRedacted =
        data["message_surface_redacted"]?.jsonPrimitive?.booleanOrNull
            ?: messageSurface?.data?.get("redacted")?.jsonPrimitive?.booleanOrNull
            ?: false
    val contextRedacted =
        data["context_surface_redacted"]?.jsonPrimitive?.booleanOrNull
            ?: contextSurface?.data?.get("redacted")?.jsonPrimitive?.booleanOrNull
            ?: false

    val exactMessageDigestVerified = when {
        messages == null || originalMessageDigest == null -> null
        messageRedacted -> null
        else -> reconstructedMessageDigest == originalMessageDigest
    }
    if (exactMessageDigestVerified == false) {
        issues += "原始 message_digest 与可回放消息 Surface 不一致"
    }

    val exactContextDigestVerified = when {
        contextMessages == null || originalContextDigest == null -> null
        contextRedacted -> null
        else -> reconstructedContextDigest == originalContextDigest
    }
    if (exactContextDigestVerified == false) {
        issues += "原始 context_digest 与可回放 Context Surface 不一致"
    }

    val reconstructedEnvelope = if (
        routeFingerprint != null &&
        expectedToolDigest != null &&
        originalContextDigest != null
    ) {
        val parts = if (version >= 2 && originalMessageDigest != null) {
            listOf(routeFingerprint, originalMessageDigest, expectedToolDigest, originalContextDigest)
        } else {
            listOf(routeFingerprint, expectedToolDigest, originalContextDigest)
        }
        stableJsonSha256(JsonArray(parts.map(::JsonPrimitive)))
    } else {
        null
    }
    val envelopeFingerprintVerified =
        reconstructedEnvelope != null && expectedEnvelope == reconstructedEnvelope
    if (!envelopeFingerprintVerified) {
        issues += "request_envelope_fingerprint 无法验证或不一致"
    }

    if (requestUid.isBlank()) issues += "request/header 缺少 request_uid"
    if (expectedToolDigest == null) issues += "request/header 缺少 tool_schema_digest"
    if (originalMessageDigest == null) issues += "request/header 缺少 message_digest"

    val status = when {
        issues.isNotEmpty() -> LocalRequestReconstructionStatus.INVALID
        version <= 1 || messageSurface == null -> LocalRequestReconstructionStatus.EVIDENCE_ONLY
        messageRedacted || contextRedacted -> LocalRequestReconstructionStatus.REDACTED_VERIFIED
        else -> LocalRequestReconstructionStatus.VERIFIED
    }

    return LocalRequestReconstruction(
        requestUid = requestUid,
        headerSequence = header.sequence,
        version = version,
        status = status,
        messages = messages,
        tools = tools,
        contextMessages = contextMessages,
        originalMessageDigest = originalMessageDigest,
        reconstructedMessageDigest = reconstructedMessageDigest,
        originalContextDigest = originalContextDigest,
        reconstructedContextDigest = reconstructedContextDigest,
        toolSchemaDigest = expectedToolDigest,
        envelopeFingerprintVerified = envelopeFingerprintVerified,
        exactMessageDigestVerified = exactMessageDigestVerified,
        exactContextDigestVerified = exactContextDigestVerified,
        model = data.string("model"),
        baseUrl = data.string("base_url"),
        profileId = data.string("profile_id"),
        provider = data.string("provider"),
        protocol = data.string("protocol"),
        temperature = data["temperature"]?.jsonPrimitive?.doubleOrNull,
        providerAttempts = emptyList(),
        issues = issues,
    )
}

private fun reconstructProviderAttempt(
    event: LocalSessionEventLog.Event,
    header: LocalSessionEventLog.Event,
    eventAt: (Long) -> LocalSessionEventLog.Event?,
): LocalProviderAttemptReconstruction {
    val data = event.data
    val issues = mutableListOf<String>()
    val headerSeq = data["header_seq"]?.jsonPrimitive?.longOrNull
    if (headerSeq != header.sequence) {
        issues += "header_seq 不匹配：期望 " + header.sequence + "，实际 " + headerSeq
    }

    fun requireSameAsHeader(field: String) {
        val expected = header.data.string(field)
        val actual = data.string(field)
        if (expected != null && actual != expected) {
            issues += field + " 与 request/header 不一致"
        }
    }
    listOf(
        "model",
        "base_url",
        "profile_id",
        "provider",
        "protocol",
        "route_fingerprint",
    ).forEach(::requireSameAsHeader)
    val headerTemperature = header.data["temperature"]?.jsonPrimitive?.doubleOrNull
    val attemptTemperature = data["temperature"]?.jsonPrimitive?.doubleOrNull
    if (headerTemperature != attemptTemperature) {
        issues += "temperature 与 request/header 不一致"
    }

    fun surface(sequenceKey: String, expectedType: String): LocalSessionEventLog.Event? {
        val sequence = data[sequenceKey]?.jsonPrimitive?.longOrNull
        if (sequence == null) {
            issues += "缺少 " + sequenceKey
            return null
        }
        if (sequence >= event.sequence) {
            issues += sequenceKey + " 必须指向 provider-attempt 之前的 Surface"
            return null
        }
        val surface = eventAt(sequence)
        if (surface == null) {
            issues += sequenceKey + " 指向不存在的事件：" + sequence
            return null
        }
        if (surface.type != expectedType) {
            issues += sequenceKey + " 类型不匹配：期望 " + expectedType + "，实际 " + surface.type
            return null
        }
        return surface
    }

    val messageSurface = surface("message_surface_seq", REQUEST_MESSAGE_SURFACE_EVENT)
    val toolSurface = surface("tool_surface_seq", REQUEST_TOOL_SURFACE_EVENT)
    val contextSurface = surface("context_surface_seq", REQUEST_CONTEXT_SURFACE_EVENT)

    val messages = messageSurface?.data?.get("messages").asObjectListOrNull()
    val tools = toolSurface?.data?.get("schemas") as? JsonArray
    val contextMessages = contextSurface?.data?.get("messages") as? JsonArray

    val replayMessageDigest = messages?.let { stableJsonSha256(JsonArray(it)) }
    val replayToolDigest = tools?.let(::stableJsonSha256)
    val replayContextDigest = contextMessages?.let(::stableJsonSha256)

    val expectedMessageSurfaceDigest = data.string("message_surface_digest")
    val expectedToolDigest = data.string("tool_schema_digest")
    val expectedContextSurfaceDigest = data.string("context_surface_digest")

    verifySurfaceDigest(
        name = "attempt-message",
        event = messageSurface,
        expectedFromHeader = expectedMessageSurfaceDigest,
        reconstructed = replayMessageDigest,
        issues = issues,
    )
    verifySurfaceDigest(
        name = "attempt-tool",
        event = toolSurface,
        expectedFromHeader = expectedToolDigest,
        reconstructed = replayToolDigest,
        issues = issues,
    )
    verifySurfaceDigest(
        name = "attempt-context",
        event = contextSurface,
        expectedFromHeader = expectedContextSurfaceDigest,
        reconstructed = replayContextDigest,
        issues = issues,
    )

    val routeFingerprint = data.string("route_fingerprint")
    val messageDigest = data.string("message_digest")
    val contextDigest = data.string("context_digest")
    val streaming = data["streaming"]?.jsonPrimitive?.booleanOrNull ?: false
    val temperature = data["temperature"]?.jsonPrimitive?.doubleOrNull
    val comparisonResponseId = data.string("prompt_cache_comparison_response_id")
    val cacheKey = data.string("prompt_cache_key")
    val cacheTtl = data.string("prompt_cache_ttl")
    val expectedFingerprint = data.string("provider_attempt_fingerprint")
    val reconstructedFingerprint = if (
        routeFingerprint != null &&
        messageDigest != null &&
        expectedToolDigest != null &&
        contextDigest != null
    ) {
        stableJsonSha256(JsonArray(listOf(
            routeFingerprint,
            messageDigest,
            expectedToolDigest,
            contextDigest,
            temperature?.toString() ?: "null",
            comparisonResponseId ?: "null",
            cacheKey ?: "null",
            cacheTtl ?: "null",
            "streaming:" + streaming,
        ).map(::JsonPrimitive)))
    } else null
    val fingerprintVerified =
        reconstructedFingerprint != null && reconstructedFingerprint == expectedFingerprint
    if (!fingerprintVerified) {
        issues += "provider_attempt_fingerprint 无法验证或不一致"
    }

    val messageRedacted =
        data["message_surface_redacted"]?.jsonPrimitive?.booleanOrNull
            ?: messageSurface?.data?.get("redacted")?.jsonPrimitive?.booleanOrNull
            ?: false
    val contextRedacted =
        data["context_surface_redacted"]?.jsonPrimitive?.booleanOrNull
            ?: contextSurface?.data?.get("redacted")?.jsonPrimitive?.booleanOrNull
            ?: false

    if (!messageRedacted && messageDigest != null && replayMessageDigest != messageDigest) {
        issues += "Provider Attempt 原始 message_digest 与 Surface 不一致"
    }
    if (!contextRedacted && contextDigest != null && replayContextDigest != contextDigest) {
        issues += "Provider Attempt 原始 context_digest 与 Surface 不一致"
    }

    return LocalProviderAttemptReconstruction(
        eventSequence = event.sequence,
        attempt = data["attempt"]?.jsonPrimitive?.intOrNull ?: 0,
        recoveryRound = data["recovery_round"]?.jsonPrimitive?.intOrNull ?: 0,
        model = data.string("model"),
        baseUrl = data.string("base_url"),
        profileId = data.string("profile_id"),
        provider = data.string("provider"),
        protocol = data.string("protocol"),
        streaming = streaming,
        temperature = temperature,
        promptCacheComparisonResponseId = comparisonResponseId,
        promptCacheKey = cacheKey,
        promptCacheTtl = cacheTtl,
        messages = messages,
        tools = tools,
        contextMessages = contextMessages,
        messageDigest = messageDigest,
        contextDigest = contextDigest,
        toolSchemaDigest = expectedToolDigest,
        fingerprintVerified = fingerprintVerified,
        redacted = messageRedacted || contextRedacted,
        issues = issues,
    )
}

private fun verifySurfaceDigest(
    name: String,
    event: LocalSessionEventLog.Event?,
    expectedFromHeader: String?,
    reconstructed: String?,
    issues: MutableList<String>,
) {
    if (event == null) return
    val eventDigest = event.data["digest"]?.jsonPrimitive?.contentOrNull
    if (eventDigest == null) {
        issues += "request/$name-surface 缺少 digest"
        return
    }
    if (expectedFromHeader != null && eventDigest != expectedFromHeader) {
        issues += "$name Surface digest 与 request/header 不一致"
    }
    if (reconstructed != null && eventDigest != reconstructed) {
        issues += "$name Surface 内容与自身 digest 不一致"
    }
}

private fun eventAtSequence(
    eventLog: LocalSessionEventLog,
    sequence: Long,
): LocalSessionEventLog.Event? =
    eventLog.pageAfter(sequence - 1L, limit = 1)
        .singleOrNull()
        ?.takeIf { it.sequence == sequence }

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun kotlinx.serialization.json.JsonElement?.asObjectListOrNull(): List<JsonObject>? {
    val array = this as? JsonArray ?: return null
    val values = array.mapNotNull { it as? JsonObject }
    return values.takeIf { it.size == array.size }
}

private const val REQUEST_HEADER_EVENT = "request/header"
private const val REQUEST_PROVIDER_ATTEMPT_EVENT = "request/provider-attempt"
private const val REQUEST_MESSAGE_SURFACE_EVENT = "request/message-surface"
private const val REQUEST_TOOL_SURFACE_EVENT = "request/tool-surface"
private const val REQUEST_CONTEXT_SURFACE_EVENT = "request/context-surface"
