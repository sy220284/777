package com.labteto.dshmobile.local.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Durable user-facing message content.
 *
 * Text stays separately projected through LocalHarnessMessage.content for search, memory and legacy
 * readers. Media lives here as lightweight local references; pixel/base64 payloads never enter the
 * transcript or session snapshot.
 */
@Serializable
sealed interface LocalMessageBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
    ) : LocalMessageBlock

    @Serializable
    @SerialName("image")
    data class Image(
        val relativePath: String,
        val mediaType: String,
        val name: String,
        val bytes: Long,
        val attachmentId: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val source: LocalMessageMediaSource = LocalMessageMediaSource.USER,
    ) : LocalMessageBlock

    @Serializable
    @SerialName("file")
    data class File(
        val relativePath: String,
        val mediaType: String,
        val name: String,
        val bytes: Long,
        val attachmentId: String? = null,
        val source: LocalMessageMediaSource = LocalMessageMediaSource.USER,
    ) : LocalMessageBlock

    /**
     * Forward-compatible content block. [payload] is the original transcript JSON and must be
     * written back verbatim so a newer producer's data is never silently destroyed.
     */
    @Serializable
    @SerialName("unknown")
    data class Unknown(
        val kind: String,
        val payload: JsonObject,
    ) : LocalMessageBlock
}

@Serializable
enum class LocalMessageMediaSource {
    USER,
    MODEL,
    TOOL,
}

internal fun LocalHarnessMessage.visibleBlocks(): List<LocalMessageBlock> =
    blocks.ifEmpty {
        content.takeIf(String::isNotBlank)
            ?.let { listOf(LocalMessageBlock.Text(it)) }
            .orEmpty()
    }

internal fun List<LocalMessageBlock>.plainTextProjection(): String =
    filterIsInstance<LocalMessageBlock.Text>()
        .joinToString("\n") { it.text }
        .trim()
