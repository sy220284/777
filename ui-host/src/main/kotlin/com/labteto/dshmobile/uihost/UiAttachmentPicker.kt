package com.labteto.dshmobile.uihost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/** Host owns platform document selection. The URI conveys the grant from the Android picker. */
@Composable
fun rememberUiAttachmentPicker(onSelected: (String?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onSelected(uri?.toString())
    }
    return { launcher.launch(arrayOf("*/*")) }
}
