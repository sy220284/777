package com.labteto.dshmobile.uihost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
