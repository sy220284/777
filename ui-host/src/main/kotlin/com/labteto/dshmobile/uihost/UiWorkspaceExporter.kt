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
    val gate = remember { UiHostDocumentOperationGate<UiWorkspaceExportRequest>() }
    val hostOwner = LocalUiHostSystemOperationOwner.current ?: remember { UiHostSystemOperationOwner() }
    val lease = remember(hostOwner) {
        UiHostSystemOperationLease(hostOwner, UiHostPlatformAction.EXPORT_WORKSPACE)
    }
    androidx.compose.runtime.DisposableEffect(gate, lease) {
        onDispose {
            // UI teardown must revoke the pending ticket, not only free the Owner.
            // A late Android picker callback may still reach this disposed instance.
            if (gate.cancelSelection() != null) lease.finish()
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) {
            if (gate.cancelSelection() != null) {
                lease.finish()
                callback(UiWorkspaceExportReceipt.Cancelled)
            }
            else callback(UiWorkspaceExportReceipt.Failed("导出请求已失效，请重新发起"))
        } else {
            val ticket = gate.selectionReturned()
            if (ticket == null) callback(UiWorkspaceExportReceipt.Failed("原导出请求已失效，请重新选择"))
            else scope.launch {
                val request = ticket.request
                val outcome = try {
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
                    result
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    UiWorkspaceExportReceipt.Failed(failure.message)
                } finally {
                    gate.finish(ticket)
                    lease.finish()
                }
                callback(outcome)
            }
        }
    }
    return { request ->
        val ticket = gate.begin(request)
        if (ticket == null) {
            callback(UiWorkspaceExportReceipt.Failed("上一个导出尚未完成，请完成或取消后重试"))
        } else if (!lease.begin()) {
            gate.failToLaunch(ticket)
            callback(UiWorkspaceExportReceipt.Failed("HOST_PLATFORM_OPERATION_BUSY"))
        } else {
            try { launcher.launch(request.displayName) }
            catch (cancelled: CancellationException) {
                gate.failToLaunch(ticket)
                lease.finish()
                throw cancelled
            }
            catch (failure: Exception) {
                gate.failToLaunch(ticket)
                lease.finish()
                callback(UiWorkspaceExportReceipt.Failed(failure.message))
            }
        }
    }
}
