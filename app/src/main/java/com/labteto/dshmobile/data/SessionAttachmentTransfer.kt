package com.labteto.dshmobile.data

import android.util.Base64
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.EncodedFileUploadRequest
import com.labteto.dshmobile.core.wire.dto.FileUploadValue
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentRequest
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns session attachment transport.
 *
 * Binary upload is preferred; the bounded encoded RPC fallback is used only when the host reports
 * that the raw-byte route is unavailable. SessionStore delegates here and keeps conversation state
 * ownership separate from transfer mechanics.
 */
internal class SessionAttachmentTransfer(
    private val apiForHost: (String?) -> DshApiClient?,
    private val onConnectionError: (String) -> Unit,
    private val logger: (String) -> Unit,
) {
    suspend fun uploadFile(
        name: String,
        size: Long,
        open: () -> InputStream?,
        onProgress: (sent: Long) -> Unit,
        targetSessionId: String?,
        targetHost: String?,
    ): RpcResult<FileUploadValue> = withContext(Dispatchers.IO) {
        val sid = targetSessionId
            ?: return@withContext RpcResult.Err(RpcError("internal", "no open session"))
        val api = apiForHost(targetHost)
            ?: return@withContext RpcResult.Err(RpcError("internal", "not connected"))
        val stream = open()
            ?: return@withContext RpcResult.Err(RpcError("internal", "could not read the file"))
        val streamed = stream.use { api.uploadFileBinary(sid, name, size, it, onProgress) }
        if (streamed !is RpcResult.Err || streamed.error.code != "capability-unavailable") {
            return@withContext streamed
        }
        if (size !in 0..MAX_ENCODED_UPLOAD_BYTES) return@withContext streamed

        logger("upload route unavailable; falling back to fileUploads/upload for ${size}B")
        val bytes = try {
            open()?.use { it.readBytesAtMost(MAX_ENCODED_UPLOAD_BYTES.toInt()) }
        } catch (tooLarge: IllegalStateException) {
            return@withContext RpcResult.Err(
                RpcError(
                    ATTACHMENT_INVALID,
                    tooLarge.message ?: "attachment exceeds fallback upload limit",
                ),
            )
        } ?: return@withContext RpcResult.Err(RpcError("internal", "could not read the file"))

        api.fileUploadEncoded(
            sid,
            EncodedFileUploadRequest(
                data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                name = name,
            ),
        ).also { onProgress(bytes.size.toLong()) }
    }

    suspend fun fetchAttachment(
        attachmentId: String,
        sessionId: String?,
        host: String?,
    ): ByteArray? {
        val sid = sessionId ?: return null
        val api = apiForHost(host) ?: return null
        return when (val result = api.sessionAttachment(SessionAttachmentRequest(sid, attachmentId))) {
            is RpcResult.Ok -> runCatching {
                Base64.decode(result.value.data, Base64.DEFAULT)
            }.getOrNull()
            is RpcResult.Err -> {
                onConnectionError(result.error.message)
                null
            }
        }
    }
}

/**
 * The host's refusal of a prompt's or command's attachments.
 * Kept beside the transfer implementation so upload fallback and prompt handling share one code.
 */
internal const val ATTACHMENT_INVALID = "session/attachment-invalid"

/** Largest file the base64 Remote fallback will carry; anything bigger needs the raw-byte route. */
internal const val MAX_ENCODED_UPLOAD_BYTES = 20L * 1024 * 1024

internal fun InputStream.readBytesAtMost(maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes 必须大于等于 0" }
    val bytes = readNBytes(maxBytes + 1)
    check(bytes.size <= maxBytes) { "附件实际大小超过 $maxBytes 字节回退上传上限" }
    return bytes
}
