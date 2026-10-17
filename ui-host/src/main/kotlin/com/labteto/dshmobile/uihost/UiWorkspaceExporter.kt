package com.labteto.dshmobile.uihost

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UiWorkspaceExportAction { SAVE, SHARE, OPEN }

/** User-selected workspace file; no arbitrary Android URI or permission is supplied by a UI pack. */
data class UiWorkspaceExportRequest(
    val path: String,
    val displayName: String,
    val action: UiWorkspaceExportAction,
)

sealed interface UiWorkspaceExportReceipt {
    data class Saved(val bytes: Long) : UiWorkspaceExportReceipt
    /** Source bytes were saved successfully but the external viewer/chooser could not open. */
    data class ExternalAppUnavailable(val bytes: Long, val message: String?) : UiWorkspaceExportReceipt
    data object Cancelled : UiWorkspaceExportReceipt
    /** The destination may contain partial bytes. Never claim this is a successful save. */
    data class Failed(val message: String?) : UiWorkspaceExportReceipt
}

/**
 * Host owns SAF destination selection, URI grants, stream lifecycle and external intents.
 * The Feature Files Owner is the only reader of workspace bytes and receives only an output
 * stream granted by the user. No UI pack reads workspace files or constructs Android Intents.
 */
@Composable
fun rememberUiWorkspaceExporter(
    writeFile: suspend (path: String, output: OutputStream) -> Long,
    onResult: (UiWorkspaceExportReceipt) -> Unit,
): (UiWorkspaceExportRequest) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onResult)
    val writer by rememberUpdatedState(writeFile)
    var pending by remember { mutableStateOf<UiWorkspaceExportRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val request = pending
        pending = null
        when {
            uri == null -> callback(UiWorkspaceExportReceipt.Cancelled)
            request == null -> callback(UiWorkspaceExportReceipt.Failed("原导出请求已失效，请重新选择"))
            else -> scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                            "无法打开所选文件保存位置"
                        }.use { output -> writer(request.path, output) }
                    }
                    val result = if (request.action == UiWorkspaceExportAction.SAVE) {
                        UiWorkspaceExportReceipt.Saved(bytes)
                    } else {
                        try {
                            val mime = context.contentResolver.getType(uri)
                                ?: java.net.URLConnection.guessContentTypeFromName(request.displayName)
                                ?: "application/octet-stream"
                            val intent = when (request.action) {
                                UiWorkspaceExportAction.SHARE -> Intent(Intent.ACTION_SEND).apply {
                                    type = mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                UiWorkspaceExportAction.OPEN -> Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                UiWorkspaceExportAction.SAVE -> error("已完成保存，不需要外部 Intent")
                            }
                            context.startActivity(if (request.action == UiWorkspaceExportAction.SHARE)
                                Intent.createChooser(intent, "分享工作区文件") else intent)
                            UiWorkspaceExportReceipt.Saved(bytes)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            UiWorkspaceExportReceipt.ExternalAppUnavailable(bytes, failure.message)
                        }
                    }
                    callback(result)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    callback(UiWorkspaceExportReceipt.Failed(failure.message))
                }
            }
        }
    }
    return { request ->
        if (pending == null) {
            pending = request
            try { launcher.launch(request.displayName) }
            catch (cancelled: CancellationException) { pending = null; throw cancelled }
            catch (failure: Exception) {
                pending = null
                callback(UiWorkspaceExportReceipt.Failed(failure.message))
            }
        }
    }
}
