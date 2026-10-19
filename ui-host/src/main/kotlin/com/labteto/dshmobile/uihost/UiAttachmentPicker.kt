package com.labteto.dshmobile.uihost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.rememberUpdatedState

/**
 * Host is the only Android document picker. Its request is admitted by the
 * same cross-feature transaction Owner as document transfer and workspace export.
 * On re-creation an unowned late picker result is refused, not assigned to the
 * current conversation or treated as a new user-granted capability.
 */
@Composable
fun rememberUiAttachmentPicker(
    mimeTypes: Array<String> = arrayOf("*/*"),
    onRejected: (String) -> Unit = {},
    onSelected: (String?) -> Unit,
): () -> Unit {
    val pick = rememberUiAttachmentPickerFor<Unit>(mimeTypes, onRejected) { _, uri -> onSelected(uri) }
    return { pick(Unit) }
}

/** Capture the Feature target before the Android picker leaves the application. */
@Composable
fun <T> rememberUiAttachmentPickerFor(
    mimeTypes: Array<String> = arrayOf("*/*"),
    onRejected: (String) -> Unit = {},
    onSelected: (T, String?) -> Unit,
): (T) -> Unit {
    require(mimeTypes.isNotEmpty()) { "文件选择器必须声明允许的类型" }
    val owner = LocalUiHostSystemOperationOwner.current
        ?: remember { UiHostSystemOperationOwner() }
    val latestSelected by rememberUpdatedState(onSelected)
    val latestRejected by rememberUpdatedState(onRejected)
    val gate = remember(owner) { UiHostDocumentOperationGate<T>() }
    val lease = remember(owner) { UiHostSystemOperationLease(owner, UiHostPlatformAction.PICK_ATTACHMENT) }
    DisposableEffect(gate, lease) {
        onDispose { if (gate.cancelSelection() != null) lease.finish() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ticket = gate.cancelSelection()
        if (ticket == null) latestRejected("HOST_PICKER_STALE_RESULT")
        else {
            lease.finish()
            latestSelected(ticket.request, uri?.toString())
        }
    }
    return { request ->
        val ticket = gate.begin(request)
        if (ticket == null) latestRejected("HOST_PLATFORM_OPERATION_BUSY")
        else if (!lease.begin()) {
            gate.failToLaunch(ticket)
            latestRejected("HOST_PLATFORM_OPERATION_BUSY")
        } else {
            try { launcher.launch(mimeTypes) }
            catch (cancelled: CancellationException) {
                gate.failToLaunch(ticket)
                lease.finish()
                throw cancelled
            }
            catch (failure: Exception) {
                gate.failToLaunch(ticket)
                lease.finish()
                latestRejected(failure.javaClass.simpleName)
            }
        }
    }
}

/**
 * Host-owned camera launch. A source session ticket and platform lease stop a
 * late camera result from silently being imported into another chat.
 * The caller must delete the temporary file after the attachment Owner has
 * finished reading it.
 */
@Composable
fun <T> rememberUiCameraCaptureFor(
    onRejected: (String) -> Unit = {},
    onSelected: (T, String, () -> Unit) -> Unit,
): (T) -> Unit {
    val context = LocalContext.current
    val owner = LocalUiHostSystemOperationOwner.current
        ?: remember { UiHostSystemOperationOwner() }
    val latestSelected by rememberUpdatedState(onSelected)
    val latestRejected by rememberUpdatedState(onRejected)
    val gate = remember(owner) { UiHostDocumentOperationGate<T>() }
    val lease = remember(owner) { UiHostSystemOperationLease(owner, UiHostPlatformAction.PICK_ATTACHMENT) }
    var captureFile by remember { androidx.compose.runtime.mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val ticket = gate.cancelSelection()
        val file = captureFile
        captureFile = null
        lease.finish()
        if (ticket != null && success && file != null) {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                latestSelected(ticket.request, uri.toString()) { file.delete() }
            } catch (failure: Exception) {
                file.delete()
                latestRejected("相机照片无法读取，请重试")
            }
        } else {
            file?.delete()
            if (ticket == null) latestRejected("HOST_PICKER_STALE_RESULT")
        }
    }
    DisposableEffect(gate, lease) {
        onDispose {
            gate.cancelSelection()
            captureFile?.delete()
            captureFile = null
            lease.finish()
        }
    }
    return { request ->
        val ticket = gate.begin(request)
        if (ticket == null) latestRejected("HOST_PLATFORM_OPERATION_BUSY")
        else if (!lease.begin()) {
            gate.failToLaunch(ticket)
            latestRejected("HOST_PLATFORM_OPERATION_BUSY")
        } else {
            try {
                val directory = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File.createTempFile("zhaoxi-", ".jpg", directory)
                captureFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                launcher.launch(uri)
            } catch (failure: Exception) {
                gate.failToLaunch(ticket)
                captureFile?.delete()
                captureFile = null
                lease.finish()
                latestRejected("相机无法打开，请检查可用的拍摄应用")
            }
        }
    }
}

/** A multiple-document picker with the same scoped Host lease as single attachments. */
@Composable
fun <T> rememberUiAttachmentMultiPickerFor(
    mimeTypes: Array<String> = arrayOf("image/*"),
    onRejected: (String) -> Unit = {},
    onSelected: (T, List<String>) -> Unit,
): (T) -> Unit {
    require(mimeTypes.isNotEmpty())
    val owner = LocalUiHostSystemOperationOwner.current
        ?: remember { UiHostSystemOperationOwner() }
    val latestSelected by rememberUpdatedState(onSelected)
    val latestRejected by rememberUpdatedState(onRejected)
    val gate = remember(owner) { UiHostDocumentOperationGate<T>() }
    val lease = remember(owner) { UiHostSystemOperationLease(owner, UiHostPlatformAction.PICK_ATTACHMENT) }
    DisposableEffect(gate, lease) {
        onDispose { if (gate.cancelSelection() != null) lease.finish() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val ticket = gate.cancelSelection()
        if (ticket == null) latestRejected("HOST_PICKER_STALE_RESULT")
        else {
            lease.finish()
            if (uris.isNotEmpty()) latestSelected(ticket.request, uris.map { it.toString() })
        }
    }
    return { request ->
        val ticket = gate.begin(request)
        if (ticket == null) latestRejected("HOST_PLATFORM_OPERATION_BUSY")
        else if (!lease.begin()) {
            gate.failToLaunch(ticket)
            latestRejected("HOST_PLATFORM_OPERATION_BUSY")
        } else {
            try { launcher.launch(mimeTypes) }
            catch (failure: Exception) {
                gate.failToLaunch(ticket)
                lease.finish()
                latestRejected("图片选择器无法打开")
            }
        }
    }
}
