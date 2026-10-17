package com.labteto.dshmobile.uihost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/** Host owns platform document selection. The URI conveys the grant from the Android picker. */
@Composable
fun rememberUiAttachmentPicker(
    onSelected: (String?) -> Unit,
    mimeTypes: Array<String> = arrayOf("*/*"),
): () -> Unit {
    require(mimeTypes.isNotEmpty()) { "文件选择器必须声明允许的类型" }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onSelected(uri?.toString())
    }
    return { launcher.launch(mimeTypes) }
}
