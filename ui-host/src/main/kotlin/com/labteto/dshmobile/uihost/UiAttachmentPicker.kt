package com.labteto.dshmobile.uihost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

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
    require(mimeTypes.isNotEmpty()) { "文件选择器必须声明允许的类型" }
    val owner = LocalUiHostSystemOperationOwner.current
        ?: remember { UiHostSystemOperationOwner() }
    val latestSelected by rememberUpdatedState(onSelected)
    val latestRejected by rememberUpdatedState(onRejected)
    var waiting by remember(owner) { mutableStateOf<UiHostSystemOperationOwner.Ticket?>(null) }
    DisposableEffect(owner) {
        onDispose {
            waiting?.let(owner::finish)
            waiting = null
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ticket = waiting
        waiting = null
        if (ticket == null || !owner.finish(ticket)) latestRejected("HOST_PICKER_STALE_RESULT")
        else latestSelected(uri?.toString())
    }
    return {
        val ticket = owner.begin(UiHostPlatformAction.PICK_ATTACHMENT)
        if (ticket == null) latestRejected("HOST_PLATFORM_OPERATION_BUSY")
        else {
            waiting = ticket
            try { launcher.launch(mimeTypes) }
            catch (failure: Exception) {
                waiting = null
                owner.finish(ticket)
                latestRejected(failure.javaClass.simpleName)
            }
        }
    }
}
