@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
)

package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Provider-neutral content block; merge-extensible by `type`. Unknown blocks are preserved. */
@Serializable(with = ContentBlockSerializer::class)
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        @SerialName("text") val text: String,
    ) : ContentBlock()

    /** Reasoning / thinking content, distinct from visible text. */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        @SerialName("text") val text: String,
    ) : ContentBlock()

    /** A durable raster image reference, valid in user or assistant content. */
    @Serializable
    @SerialName("image")
    data class Image(
        @SerialName("attachment") val attachment: ImageAttachmentRef,
    ) : ContentBlock()

    /**
     * A durable verbatim file reference, valid in user content (harness 0.1.3). Files never
     * reach a provider natively — request assembly projects each to handle text — so the
     * durable log keeps the structured reference for presentation only.
     */
    @Serializable
    @SerialName("file")
    data class File(
        @SerialName("attachment") val attachment: FileAttachmentRef,
    ) : ContentBlock()

    /** A tool invocation requested by the model. */
    @Serializable
    @SerialName("tool-call")
    data class ToolCall(
        /** Provider-issued call id; correlates with the matching tool result. */
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        /** Raw JSON string as produced by the model. */
        @SerialName("arguments") val arguments: String,
    ) : ContentBlock()

    /** The result of a tool invocation, sent back to the model. */
    @Serializable
    @SerialName("tool-result")
    data class ToolResult(
        @SerialName("toolCallId") val toolCallId: String,
        @SerialName("content") val content: List<ContentBlock> = emptyList(),
        @SerialName("isError") val isError: Boolean? = null,
    ) : ContentBlock()
}

/** A content block of an unknown `type`, preserved verbatim. */
data class UnknownContentBlock(
    val type: String,
    val raw: JsonElement,
) : ContentBlock()

/** Custom `type`-dispatching serializer for [ContentBlock]; unknown blocks pass through raw. */
object ContentBlockSerializer : KSerializer<ContentBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ContentBlock") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: ContentBlock) {
        val json: JsonElement = when (value) {
            is ContentBlock.Text -> encodeWithType(ContentBlock.Text.serializer(), value)
            is ContentBlock.Reasoning -> encodeWithType(ContentBlock.Reasoning.serializer(), value)
            is ContentBlock.Image -> encodeWithType(ContentBlock.Image.serializer(), value)
            is ContentBlock.File -> encodeWithType(ContentBlock.File.serializer(), value)
            is ContentBlock.ToolCall -> encodeWithType(ContentBlock.ToolCall.serializer(), value)
            is ContentBlock.ToolResult -> encodeWithType(ContentBlock.ToolResult.serializer(), value)
            is UnknownContentBlock -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): ContentBlock {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "text" -> decodeFromJsonElement(ContentBlock.Text.serializer(), json)
            "reasoning" -> decodeFromJsonElement(ContentBlock.Reasoning.serializer(), json)
            "image" -> decodeFromJsonElement(ContentBlock.Image.serializer(), json)
            "file" -> decodeFromJsonElement(ContentBlock.File.serializer(), json)
            "tool-call" -> decodeFromJsonElement(ContentBlock.ToolCall.serializer(), json)
            "tool-result" -> decodeFromJsonElement(ContentBlock.ToolResult.serializer(), json)
            else -> UnknownContentBlock(type, json)
        }
    }
}

/** Raw streaming protocol emitted by adapters; merge-extensible by `type`. */
@Serializable(with = StreamChunkSerializer::class)
sealed class StreamChunk {
    @Serializable
    @SerialName("block-start")
    data class BlockStart(
        @SerialName("index") val index: Int,
        @SerialName("blockType") val blockType: String,
    ) : StreamChunk()

    @Serializable
    @SerialName("text-delta")
    data class TextDelta(
        @SerialName("index") val index: Int,
        @SerialName("text") val text: String,
    ) : StreamChunk()

    @Serializable
    @SerialName("reasoning-delta")
    data class ReasoningDelta(
        @SerialName("index") val index: Int,
        @SerialName("text") val text: String,
    ) : StreamChunk()

    @Serializable
    @SerialName("tool-call-delta")
    data class ToolCallDelta(
        @SerialName("index") val index: Int,
        @SerialName("id") val id: String,
        @SerialName("name") val name: String? = null,
        @SerialName("argumentsDelta") val argumentsDelta: String = "",
    ) : StreamChunk()

    @Serializable
    @SerialName("block-end")
    data class BlockEnd(
        @SerialName("index") val index: Int,
        @SerialName("block") val block: ContentBlock,
    ) : StreamChunk()

    @Serializable
    @SerialName("usage")
    data class Usage(
        @SerialName("usage") val usage: TokenUsage,
    ) : StreamChunk()

    @Serializable
    @SerialName("finish")
    data class Finish(
        @SerialName("reason") val reason: FinishReason,
        /** Adapter-private lossless-JSON state for replaying a successful response. */
        @SerialName("replayState") val replayState: JsonElement? = null,
    ) : StreamChunk()
}

/** A stream chunk of an unknown `type`, preserved verbatim. */
data class UnknownStreamChunk(
    val type: String,
    val raw: JsonElement,
) : StreamChunk()

/** Custom `type`-dispatching serializer for [StreamChunk]; unknown chunks pass through raw. */
object StreamChunkSerializer : KSerializer<StreamChunk> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("StreamChunk") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: StreamChunk) {
        val json: JsonElement = when (value) {
            is StreamChunk.BlockStart -> encodeWithType(StreamChunk.BlockStart.serializer(), value)
            is StreamChunk.TextDelta -> encodeWithType(StreamChunk.TextDelta.serializer(), value)
            is StreamChunk.ReasoningDelta -> encodeWithType(StreamChunk.ReasoningDelta.serializer(), value)
            is StreamChunk.ToolCallDelta -> encodeWithType(StreamChunk.ToolCallDelta.serializer(), value)
            is StreamChunk.BlockEnd -> encodeWithType(StreamChunk.BlockEnd.serializer(), value)
            is StreamChunk.Usage -> encodeWithType(StreamChunk.Usage.serializer(), value)
            is StreamChunk.Finish -> encodeWithType(StreamChunk.Finish.serializer(), value)
            is UnknownStreamChunk -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): StreamChunk {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "block-start" -> decodeFromJsonElement(StreamChunk.BlockStart.serializer(), json)
            "text-delta" -> decodeFromJsonElement(StreamChunk.TextDelta.serializer(), json)
            "reasoning-delta" -> decodeFromJsonElement(StreamChunk.ReasoningDelta.serializer(), json)
            "tool-call-delta" -> decodeFromJsonElement(StreamChunk.ToolCallDelta.serializer(), json)
            "block-end" -> decodeFromJsonElement(StreamChunk.BlockEnd.serializer(), json)
            "usage" -> decodeFromJsonElement(StreamChunk.Usage.serializer(), json)
            "finish" -> decodeFromJsonElement(StreamChunk.Finish.serializer(), json)
            else -> UnknownStreamChunk(type, json)
        }
    }
}

/**
 * The concrete serializers above do not emit the sealed class discriminator on their own.
 * Re-add it whenever a typed wire value is converted back to JSON; the transcript fold consumes
 * that JSON and otherwise sees every known block/chunk as an empty `unknown` value.
 */
private fun <T> encodeWithType(serializer: SerializationStrategy<T>, value: T): JsonElement =
    encodeToJsonElement(serializer, value).withType(serializer.descriptor.serialName)

private fun JsonElement.withType(type: String): JsonElement = JsonObject(
    linkedMapOf<String, JsonElement>().apply {
        putAll(this@withType.jsonObject)
        put("type", JsonPrimitive(type))
    },
)
