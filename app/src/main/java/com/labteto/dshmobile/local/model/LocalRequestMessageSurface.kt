package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Request replay evidence is an immutable, append-only chain.
 * Full checkpoints bound replay work; intervening surfaces only store the changed suffix.
 * All digests always cover the COMPLETE reconstructed model-visible messages.
 */
internal const val REQUEST_MESSAGE_SURFACE_VERSION = 4
internal const val REQUEST_MESSAGE_DELTA_MAX_DEPTH = 32

internal data class LocalRequestMessageSurfaceState(
    val sequence: Long,
    val messages: List<JsonObject>,
    val digest: String,
    val depth: Int,
    val redacted: Boolean,
)

internal data class LocalRequestMessageSurfaceEncoding(
    val payload: JsonObject,
    val depth: Int,
    val bytesSaved: Int,
)

internal fun encodeLocalRequestMessageSurface(
    messages: List<JsonObject>,
    digest: String,
    redacted: Boolean,
    previous: LocalRequestMessageSurfaceState? = null,
): LocalRequestMessageSurfaceEncoding {
    check(stableJsonSha256(JsonArray(messages)) == digest) {
        "请求消息摘要与准备写入的内容不一致"
    }
    val full = buildJsonObject {
        put("version", REQUEST_MESSAGE_SURFACE_VERSION)
        put("digest", digest)
        put("messages", JsonArray(messages))
        put("redacted", redacted)
    }
    if (previous == null || previous.depth >= REQUEST_MESSAGE_DELTA_MAX_DEPTH) {
        return LocalRequestMessageSurfaceEncoding(full, 0, 0)
    }
    val keep = previous.messages.zip(messages).takeWhile { (old, now) -> old == now }.size
    if (keep == 0) return LocalRequestMessageSurfaceEncoding(full, 0, 0)
    val delta = buildJsonObject {
        put("version", REQUEST_MESSAGE_SURFACE_VERSION)
        put("digest", digest)
        put("base_surface_seq", previous.sequence)
        put("keep", keep)
        put("tail", JsonArray(messages.drop(keep)))
        put("depth", previous.depth + 1)
        put("redacted", redacted)
    }
    val saved = full.toString().toByteArray(Charsets.UTF_8).size -
        delta.toString().toByteArray(Charsets.UTF_8).size
    return if (saved > 0) {
        LocalRequestMessageSurfaceEncoding(delta, previous.depth + 1, saved)
    } else {
        LocalRequestMessageSurfaceEncoding(full, 0, 0)
    }
}

/**
 * Validates EVERY link, its order, depth and complete-message digest. Legacy V2/V3 full
 * surfaces remain valid without rewriting their externally referenced sequence numbers.
 */
internal fun replayLocalRequestMessageSurface(
    surface: LocalSessionEventLog.Event,
    eventAt: (Long) -> LocalSessionEventLog.Event?,
    issues: MutableList<String>,
): List<JsonObject>? {
    val backwards = ArrayList<LocalSessionEventLog.Event>()
    val visited = HashSet<Long>()
    var cursor = surface
    while (true) {
        if (cursor.type != "request/message-surface" || !visited.add(cursor.sequence)) {
            issues += "request/message-surface 引用发生循环或类型不匹配：${cursor.sequence}"
            return null
        }
        backwards += cursor
        if (backwards.size > REQUEST_MESSAGE_DELTA_MAX_DEPTH + 1) {
            issues += "request/message-surface 引用链超过安全深度"
            return null
        }
        val parent = (cursor.data["base_surface_seq"] as? JsonPrimitive)?.longOrNull
        if (parent == null) break
        if (parent < 0L || parent >= cursor.sequence) {
            issues += "request/message-surface 的 base_surface_seq 必须指向更早事件"
            return null
        }
        cursor = eventAt(parent) ?: run {
            issues += "request/message-surface 引用的前置事件不存在：$parent"
            return null
        }
    }
    var content = emptyList<JsonObject>()
    var previousSequence: Long? = null
    var depth = 0
    for (event in backwards.asReversed()) {
        val data = event.data
        val parent = (data["base_surface_seq"] as? JsonPrimitive)?.longOrNull
        if (parent == null) {
            if ("keep" in data || "tail" in data || "base_surface_seq" in data ||
                ((data["depth"] as? JsonPrimitive)?.intOrNull ?: 0) != 0
            ) {
                issues += "request/message-surface 完整快照结构不合法：${event.sequence}"
                return null
            }
            content = objectMessages(data["messages"]) ?: run {
                issues += "request/message-surface 缺少合法 messages：${event.sequence}"
                return null
            }
            depth = 0
        } else {
            if (parent != previousSequence || "messages" in data) {
                issues += "request/message-surface 增量引用不连续：${event.sequence}"
                return null
            }
            val keep = (data["keep"] as? JsonPrimitive)?.intOrNull
            val tail = objectMessages(data["tail"])
            val advertisedDepth = (data["depth"] as? JsonPrimitive)?.intOrNull
            if (keep == null || keep < 0 || keep > content.size ||
                tail == null || advertisedDepth != depth + 1
            ) {
                issues += "request/message-surface 增量内容或深度不合法：${event.sequence}"
                return null
            }
            content = content.take(keep) + tail
            depth = advertisedDepth
        }
        val digest = (data["digest"] as? JsonPrimitive)?.contentOrNull
        if (digest == null || stableJsonSha256(JsonArray(content)) != digest) {
            issues += "request/message-surface 完整消息摘要校验失败：${event.sequence}"
            return null
        }
        previousSequence = event.sequence
    }
    return content
}

private fun objectMessages(value: kotlinx.serialization.json.JsonElement?): List<JsonObject>? {
    val array = value as? JsonArray ?: return null
    val objects = array.mapNotNull { it as? JsonObject }
    return objects.takeIf { it.size == array.size }
}
