package com.labteto.dshmobile.ui.screens.local

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.foundation.SyMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun WorkspaceDirectoryArchiveAction(
    sourceDirectory: String,
    scopeKey: String,
    enabled: Boolean,
    exportArchive: suspend (String, Uri) -> Int,
    onSaved: (String) -> Unit,
    onError: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val failed = stringResource(R.string.local_files_zip_failed)
    val cancelled = stringResource(R.string.local_files_zip_cancelled)
    var pendingDirectory by rememberSaveable(scopeKey) { mutableStateOf<String?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destination ->
        val selected = pendingDirectory
        pendingDirectory = null
        if (destination != null && selected != null) {
            exportJob = scope.launch {
                onError(null)
                try {
                    val count = exportArchive(selected, destination)
                    onSaved(resources.getString(R.string.local_files_zip_complete, count))
                } catch (error: CancellationException) {
                    onError(cancelled)
                    throw error
                } catch (error: Exception) {
                    onError(error.message ?: failed)
                } finally {
                    exportJob = null
                }
            }
        }
    }
    val exporting = exportJob?.isActive == true
    if (sourceDirectory.isBlank() && !exporting) return
    SyButton(
        text = stringResource(if (exporting) R.string.common_cancel else R.string.local_files_zip_folder),
        onClick = {
            if (exporting) exportJob?.cancel()
            else {
                pendingDirectory = sourceDirectory
                launcher.launch(sourceDirectory.substringAfterLast('/') + ".zip")
            }
        },
        enabled = exporting || enabled && pendingDirectory == null,
        modifier = Modifier.padding(horizontal = SyMetric.gapLg),
        size = SyButtonSize.Small,
        variant = SyButtonVariant.Outline,
    )
}
