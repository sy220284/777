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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) callback(UiDocumentPickReceipt.Cancelled)
        else {
            callback(UiDocumentPickReceipt.Reading)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
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
                                require(out.size().toLong() + count <= maxBytes) { "文档超过该功能允许的大小" }
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                        UiDocumentPickReceipt.Picked(UiPickedDocument(name, resolver.getType(uri), bytes))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { UiDocumentPickReceipt.Failed(failure.message) }
                }
                callback(result)
            }
        }
    }
    return {
        try { launcher.launch(arrayOf("*/*")) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { callback(UiDocumentPickReceipt.Failed(failure.message)) }
    }
}

/** Native destination selection and byte writing, with no private business storage access. */
@Composable
fun rememberUiDocumentSaver(mimeType: String, onResult: (UiDocumentWriteReceipt) -> Unit): (UiExportFile) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onResult)
    var pending by remember(mimeType) { mutableStateOf<UiExportFile?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        val payload = pending
        pending = null
        when {
            uri == null -> callback(UiDocumentWriteReceipt.Cancelled)
            payload == null -> callback(UiDocumentWriteReceipt.Failed("导出内容已失效，请重新选择导出"))
            else -> scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入所选文档" }.use { output ->
                            var offset = 0
                            while (offset < payload.bytes.size) {
                                coroutineContext.ensureActive()
                                val count = minOf(DEFAULT_BUFFER_SIZE, payload.bytes.size - offset)
                                output.write(payload.bytes, offset, count)
                                offset += count
                            }
                        }
                        UiDocumentWriteReceipt.Saved
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { UiDocumentWriteReceipt.Failed(failure.message) }
                }
                callback(result)
            }
        }
    }
    return { file ->
        if (pending == null) {
            pending = file
            try { launcher.launch(file.name) }
            catch (cancelled: CancellationException) { pending = null; throw cancelled }
            catch (failure: Exception) { pending = null; callback(UiDocumentWriteReceipt.Failed(failure.message)) }
        }
    }
}
