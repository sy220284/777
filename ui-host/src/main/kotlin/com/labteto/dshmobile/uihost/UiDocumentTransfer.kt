package com.labteto.dshmobile.uihost

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class UiExportFile(val name: String, val bytes: ByteArray)
data class UiPickedDocument(val name: String?, val mimeType: String?, val bytes: ByteArray)
sealed interface UiDocumentPickReceipt {
    data object Reading : UiDocumentPickReceipt
    data class Picked(val document: UiPickedDocument) : UiDocumentPickReceipt
    data object Cancelled : UiDocumentPickReceipt
    data class Failed(val message: String?) : UiDocumentPickReceipt
}
sealed interface UiDocumentWriteReceipt {
    data object Saved : UiDocumentWriteReceipt
    data object Cancelled : UiDocumentWriteReceipt
    /** The provider may have written part of the document; never report Saved on failure. */
    data class Failed(val message: String?) : UiDocumentWriteReceipt
}

/** Only user-granted document URIs. Content size policy is supplied by the Feature owner. */
@Composable
fun rememberUiDocumentImporter(maxBytes: Int, onResult: (UiDocumentPickReceipt) -> Unit): () -> Unit {
    require(maxBytes > 0)
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onResult)
    val gate = remember { UiHostDocumentOperationGate<Int>() }
    val hostOwner = LocalUiHostSystemOperationOwner.current ?: remember { UiHostSystemOperationOwner() }
    val lease = remember(hostOwner) {
        UiHostSystemOperationLease(hostOwner, UiHostPlatformAction.IMPORT_DOCUMENT)
    }
    DisposableEffect(gate, lease) {
        onDispose {
            // UI teardown must revoke the pending ticket, not only free the Owner.
            // A late Android picker callback may still reach this disposed instance.
            if (gate.cancelSelection() != null) lease.finish()
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            if (gate.cancelSelection() != null) {
                lease.finish()
                callback(UiDocumentPickReceipt.Cancelled)
            }
            else callback(UiDocumentPickReceipt.Failed("文档选择请求已失效，请重新选择"))
        } else {
            val ticket = gate.selectionReturned()
            if (ticket == null) callback(UiDocumentPickReceipt.Failed("文档请求已失效，请重新选择"))
            else {
            callback(UiDocumentPickReceipt.Reading)
            scope.launch {
                val result = try { withContext(Dispatchers.IO) {
                    try {
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        }
                        val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法读取所选文档" }.use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(out.size().toLong() + count <= ticket.request) { "文档超过该功能允许的大小" }
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                        UiDocumentPickReceipt.Picked(UiPickedDocument(name, resolver.getType(uri), bytes))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { UiDocumentPickReceipt.Failed(failure.message) }
                } } finally {
                    gate.finish(ticket)
                    lease.finish()
                }
                callback(result)
            }
            }
        }
    }
    return {
        val ticket = gate.begin(maxBytes)
        if (ticket == null) {
            callback(UiDocumentPickReceipt.Failed("上一个文档正在读取，请先完成或取消该请求"))
        } else if (!lease.begin()) {
            gate.failToLaunch(ticket)
            callback(UiDocumentPickReceipt.Failed("HOST_PLATFORM_OPERATION_BUSY"))
        } else {
            try { launcher.launch(arrayOf("*/*")) }
            catch (cancelled: CancellationException) {
                gate.failToLaunch(ticket)
                lease.finish()
                throw cancelled
            }
            catch (failure: Exception) {
                gate.failToLaunch(ticket)
                lease.finish()
                callback(UiDocumentPickReceipt.Failed(failure.message))
            }
        }
    }
}

/** Byte exports and streaming media share the same Host destination workflow. */
@Composable
fun rememberUiDocumentSaver(mimeType: String, onResult: (UiDocumentWriteReceipt) -> Unit): (UiExportFile) -> Unit =
    rememberUiStreamDocumentSaver(
        mimeType = mimeType,
        filename = { file: UiExportFile -> file.name },
        write = { file, output ->
            var offset = 0
            while (offset < file.bytes.size) {
                coroutineContext.ensureActive()
                val count = minOf(DEFAULT_BUFFER_SIZE, file.bytes.size - offset)
                output.write(file.bytes, offset, count)
                offset += count
            }
            UiDocumentWriteReceipt.Saved
        },
        onResult = { _, receipt -> onResult(receipt) },
        onRejected = onResult,
    )

/**
 * The request captured before opening SAF also owns the subsequent write.
 * Large media stays streamed by its Feature Owner; the Host owns the URI and
 * output lifecycle. A rejected Owner write is never promoted to Saved.
 */
@Composable
fun <T> rememberUiStreamDocumentSaver(
    mimeType: String,
    filename: (T) -> String,
    write: suspend (T, java.io.OutputStream) -> UiDocumentWriteReceipt,
    onResult: (T, UiDocumentWriteReceipt) -> Unit,
    onRejected: (UiDocumentWriteReceipt.Failed) -> Unit,
): (T) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onResult)
    val rejected by rememberUpdatedState(onRejected)
    // Capture the writer as well as its payload: recomposition cannot retarget an open picker.
    val gate = remember { UiHostDocumentOperationGate<Pair<T, suspend (T, java.io.OutputStream) -> UiDocumentWriteReceipt>>() }
    val hostOwner = LocalUiHostSystemOperationOwner.current ?: remember { UiHostSystemOperationOwner() }
    val lease = remember(hostOwner) {
        UiHostSystemOperationLease(hostOwner, UiHostPlatformAction.SAVE_DOCUMENT)
    }
    DisposableEffect(gate, lease) {
        onDispose {
            if (gate.cancelSelection() != null) lease.finish()
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        if (uri == null) {
            val ticket = gate.cancelSelection()
            if (ticket == null) rejected(UiDocumentWriteReceipt.Failed("HOST_PICKER_STALE_RESULT"))
            else {
                lease.finish()
                callback(ticket.request.first, UiDocumentWriteReceipt.Cancelled)
            }
        } else {
            val ticket = gate.selectionReturned()
            // After process recreation no business request exists to resume or write.
            if (ticket == null) rejected(UiDocumentWriteReceipt.Failed("HOST_PICKER_STALE_RESULT"))
            else scope.launch {
                val (payload, writer) = ticket.request
                val result = try { withContext(Dispatchers.IO) {
                    try {
                        writeUiDocumentStream(payload, { resolver.openOutputStream(uri, "wt") }, writer)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { UiDocumentWriteReceipt.Failed(failure.message) }
                } } finally {
                    gate.finish(ticket)
                    lease.finish()
                }
                callback(payload, result)
            }
        }
    }
    return { payload ->
        val ticket = gate.begin(payload to write)
        if (ticket == null) {
            callback(payload, UiDocumentWriteReceipt.Failed("上一个文件保存尚未结束，请完成或取消后重试"))
        } else if (!lease.begin()) {
            gate.failToLaunch(ticket)
            callback(payload, UiDocumentWriteReceipt.Failed("HOST_PLATFORM_OPERATION_BUSY"))
        } else {
            try { launcher.launch(filename(payload)) }
            catch (cancelled: CancellationException) {
                gate.failToLaunch(ticket)
                lease.finish()
                throw cancelled
            }
            catch (failure: Exception) {
                gate.failToLaunch(ticket)
                lease.finish()
                callback(payload, UiDocumentWriteReceipt.Failed(failure.message))
            }
        }
    }
}

/** Close errors are propagated too: a provider must finish before Saved reaches the UI. */
suspend fun <T> writeUiDocumentStream(
    request: T,
    openOutput: () -> java.io.OutputStream?,
    write: suspend (T, java.io.OutputStream) -> UiDocumentWriteReceipt,
): UiDocumentWriteReceipt =
    requireNotNull(openOutput()) { "无法写入所选文档" }.use { output -> write(request, output) }
