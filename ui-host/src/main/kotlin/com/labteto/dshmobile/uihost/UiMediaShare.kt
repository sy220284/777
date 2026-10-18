package com.labteto.dshmobile.uihost

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.labteto.dshmobile.uicontract.MediaUiPort
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Re-resolve session ownership at the user action, never share a renderer-supplied raw path. */
suspend fun shareUiMedia(context: Context, media: MediaUiPort, id: String, sessionId: String) {
    val intent = withContext(Dispatchers.IO) {
        val asset = requireNotNull(media.assetForSession(id, sessionId)) { "媒体已不可用" }
        val path = asset.localPath
        Intent(Intent.ACTION_SEND).apply {
            if (path != null) {
                val file = File(path)
                require(file.isFile) { "媒体文件不存在" }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                clipData = ClipData.newRawUri(file.name, uri)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, asset.textOutput ?: asset.remoteUrl ?: error("媒体没有可分享内容"))
            }
        }
    }
    withContext(Dispatchers.Main.immediate) { context.startActivity(Intent.createChooser(intent, "分享媒体")) }
}

/** The Portrait owner still validates this provider URI before import. */
fun ownedUiMediaUri(context: Context, media: MediaUiPort, id: String, sessionId: String): String {
    val asset = requireNotNull(media.assetForSession(id, sessionId)) { "媒体已不可用" }
    val file = File(requireNotNull(asset.localPath) { "媒体尚未保存到本机" })
    require(file.isFile) { "媒体文件不存在" }
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
}
