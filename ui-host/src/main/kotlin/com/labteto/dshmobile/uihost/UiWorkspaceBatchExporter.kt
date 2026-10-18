package com.labteto.dshmobile.uihost

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Host creates one SAF destination for the user's explicit multi-file selection. */
data class UiWorkspaceBatchExportRequest(
    val paths: List<String>,
    val action: UiWorkspaceExportAction,
    val archiveName: String = "朝夕工作区文件.zip",
)

/** Names are display-only ZIP members; the Files Owner still authorizes every source path. */
internal fun uiWorkspaceZipMemberNames(paths: List<String>): List<String> {
    require(paths.isNotEmpty() && paths.size <= 100) { "一次最多分享 100 个文件" }
    require(paths.distinct().size == paths.size) { "请勿重复选择文件" }
    val used = mutableSetOf<String>()
    return paths.map { path ->
        val basename = path.replace('\\', '/').substringAfterLast('/').ifBlank { "file" }
            .replace(Regex("[\u0000-\u001f\u007f]"), "_")
            .take(150).ifBlank { "file" }
        val dot = basename.lastIndexOf('.').takeIf { it > 0 } ?: basename.length
        val base = basename.substring(0, dot)
        val suffix = basename.substring(dot)
        var candidate = basename
        var number = 2
        while (!used.add(candidate.lowercase(java.util.Locale.ROOT))) {
            candidate = "$base ($number)$suffix"
            number++
        }
        candidate
    }
}

/** Saves each selected file through FilesUiPort's real owner into a single user-granted ZIP. */
@Composable
fun rememberUiWorkspaceBatchExporter(
    writeFile: suspend (path: String, output: OutputStream) -> Long,
    onResult: (UiWorkspaceExportReceipt) -> Unit,
): (UiWorkspaceBatchExportRequest) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onResult)
    val writer by rememberUpdatedState(writeFile)
    val gate = remember { UiHostDocumentOperationGate<UiWorkspaceBatchExportRequest>() }
    val hostOwner = LocalUiHostSystemOperationOwner.current ?: remember { UiHostSystemOperationOwner() }
    val lease = remember(hostOwner) { UiHostSystemOperationLease(hostOwner, UiHostPlatformAction.EXPORT_WORKSPACE) }
    DisposableEffect(gate, lease) {
        onDispose { if (gate.cancelSelection() != null) lease.finish() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) {
            if (gate.cancelSelection() != null) {
                lease.finish()
                callback(UiWorkspaceExportReceipt.Cancelled)
            }
        } else {
            val ticket = gate.selectionReturned()
            if (ticket == null) callback(UiWorkspaceExportReceipt.Failed("分享请求已失效，请重新发起"))
            else scope.launch {
                val request = ticket.request
                val receipt = try {
                    val names = uiWorkspaceZipMemberNames(request.paths)
                    val total = withContext(Dispatchers.IO) {
                        var count = 0L
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                            "无法写入所选文件位置"
                        }.use { sink ->
                            ZipOutputStream(sink).use { zip ->
                                for ((index, path) in request.paths.withIndex()) {
                                    zip.putNextEntry(ZipEntry(names[index]))
                                    count += writer(path, zip)
                                    zip.closeEntry()
                                }
                            }
                        }
                        count
                    }
                    if (request.action == UiWorkspaceExportAction.SHARE) {
                        try {
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "分享工作区文件包"))
                            UiWorkspaceExportReceipt.Saved(total)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            UiWorkspaceExportReceipt.ExternalAppUnavailable(total, failure.message)
                        }
                    } else UiWorkspaceExportReceipt.Saved(total)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    // A failed source may leave the user-selected destination with partial bytes.
                    UiWorkspaceExportReceipt.Failed(failure.message)
                } finally {
                    gate.finish(ticket)
                    lease.finish()
                }
                callback(receipt)
            }
        }
    }
    return { request ->
        val valid = runCatching {
            require(request.action != UiWorkspaceExportAction.OPEN) { "压缩包不支持此处直接打开" }
            uiWorkspaceZipMemberNames(request.paths)
        }
        if (valid.isFailure) callback(UiWorkspaceExportReceipt.Failed(valid.exceptionOrNull()?.message))
        else {
            val ticket = gate.begin(request)
            if (ticket == null) callback(UiWorkspaceExportReceipt.Failed("上一项文件操作尚未完成"))
            else if (!lease.begin()) {
                gate.failToLaunch(ticket)
                callback(UiWorkspaceExportReceipt.Failed("文件选择器忙碌，请稍后重试"))
            } else try {
                val name = request.archiveName.substringAfterLast('/').takeIf { it.endsWith(".zip", true) }
                    ?: "朝夕工作区文件.zip"
                picker.launch(name)
            } catch (cancelled: CancellationException) {
                gate.failToLaunch(ticket)
                lease.finish()
                throw cancelled
            } catch (failure: Exception) {
                gate.failToLaunch(ticket)
                lease.finish()
                callback(UiWorkspaceExportReceipt.Failed(failure.message))
            }
        }
    }
}
