package com.labteto.dshmobile.ui.screens.local

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Camera results belong to the session that opened the camera, including after recreation. */
@Composable
internal fun rememberLocalCameraCapture(
    sessionId: String,
    onCaptured: (Uri, () -> Unit) -> Unit,
    onFailure: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentSession by rememberUpdatedState(sessionId)
    val captured by rememberUpdatedState(onCaptured)
    val failure by rememberUpdatedState(onFailure)
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSession by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionSession by rememberSaveable { mutableStateOf<String?>(null) }
    fun clear() {
        pendingPath?.let { File(it).delete() }
        pendingPath = null
        pendingSession = null
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pendingPath
        if (success && path != null && currentSession == pendingSession) {
            val file = File(path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            pendingPath = null
            pendingSession = null
            captured(uri) { file.delete() }
        } else {
            clear()
        }
    }
    val launch: () -> Unit = {
        try {
            clear()
            val directory = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("capture-", ".jpg", directory)
            pendingPath = file.absolutePath
            pendingSession = currentSession
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
        } catch (_: Exception) {
            clear()
            failure()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (permissionSession == currentSession) { if (granted) launch() else failure() }
        permissionSession = null
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launch()
        } else {
            permissionSession = currentSession
            permission.launch(Manifest.permission.CAMERA)
        }
    }
}
