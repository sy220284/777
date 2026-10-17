package com.labteto.dshmobile.uihost

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.labteto.dshmobile.uicontract.UiSystemPermission
import com.labteto.dshmobile.uicontract.UiSystemPermissionReceipt
import kotlinx.coroutines.CancellationException

private fun UiSystemPermission.androidName(): String = when (this) {
    UiSystemPermission.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
    UiSystemPermission.IMAGES -> Manifest.permission.READ_MEDIA_IMAGES
    UiSystemPermission.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
    UiSystemPermission.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
    UiSystemPermission.SELECTED_VISUAL -> Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    UiSystemPermission.CAMERA -> Manifest.permission.CAMERA
}

/** The Host owns runtime permission dialogs and does not replay one after recreation. */
@Composable
fun rememberUiSystemPermissionRequester(
    onResult: (UiSystemPermissionReceipt) -> Unit,
): (Set<UiSystemPermission>) -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    val owner = LocalUiHostSystemOperationOwner.current ?: remember { UiHostSystemOperationOwner() }
    val gate = remember(owner) { UiHostDocumentOperationGate<Set<UiSystemPermission>>() }
    val lease = remember(owner) { UiHostSystemOperationLease(owner, UiHostPlatformAction.REQUEST_PERMISSION) }
    DisposableEffect(gate, lease) {
        onDispose { if (gate.cancelSelection() != null) lease.finish() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val ticket = gate.cancelSelection()
        if (ticket == null) callback(UiSystemPermissionReceipt.Interrupted)
        else {
            lease.finish()
            val granted = ticket.request.filterTo(linkedSetOf()) {
                context.checkSelfPermission(it.androidName()) == PackageManager.PERMISSION_GRANTED
            }
            callback(UiSystemPermissionReceipt.Completed(ticket.request, granted))
        }
    }
    return { requested ->
        require(requested.isNotEmpty())
        val ticket = gate.begin(requested.toSet())
        if (ticket == null) callback(UiSystemPermissionReceipt.Busy)
        else if (!lease.begin()) {
            gate.failToLaunch(ticket)
            callback(UiSystemPermissionReceipt.Busy)
        } else {
            try { launcher.launch(ticket.request.map { it.androidName() }.toTypedArray()) }
            catch (cancelled: CancellationException) {
                gate.failToLaunch(ticket); lease.finish(); throw cancelled
            }
            catch (failure: Exception) {
                gate.failToLaunch(ticket); lease.finish()
                callback(UiSystemPermissionReceipt.Failed(failure.message))
            }
        }
    }
}
