package com.labteto.dshmobile.uipack.v6

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.model.LocalMediaPage
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.MediaUiReceipt
import com.labteto.dshmobile.uicontract.UiDocumentMediaKind
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiMediaAsset
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Never use MediaUiPort.asset() here: it lacks a session ownership check. */
internal fun resolveV6DocumentMediaAsset(
    media: MediaUiPort?,
    node: UiDocumentNode.Media,
    sessionId: String,
): UiMediaAsset? = media?.assetForSession(node.assetId, sessionId)
    ?.takeIf { it.kind == node.kind }

/** Only session-fenced Media Owner references may enter a native decoder. */
@Composable
internal fun V6DocumentMediaNode(node: UiDocumentNode.Media, sessionId: String, media: MediaUiPort?) {
    val updates = remember(media, sessionId) {
        media?.observe(sessionId, media.pageSize) ?: flowOf(LocalMediaPage())
    }
    val page by updates.collectAsStateWithLifecycle(initialValue = LocalMediaPage())
    val asset = remember(media, sessionId, node.assetId, node.kind, page) {
        resolveV6DocumentMediaAsset(media, node, sessionId)
    }
    val path = asset?.localPath
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(node.label)
        if (path == null || media == null) {
            Text("此会话的媒体文件尚未保存在设备上，或当前访问未获授权。")
        } else {
            V6MediaExport(node, sessionId, media, path)
            when (node.kind) {
                UiDocumentMediaKind.IMAGE -> {
                    val bitmap by produceState<ImageBitmap?>(null, path) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(path, bounds)
                                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                                    var sample = 1
                                    while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
                                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                                        inSampleSize = sample
                                    })?.asImageBitmap()
                                }
                            }.getOrNull()
                        }
                    }
                    if (bitmap == null) Text("图片载入中或无法解码")
                    else Image(bitmap!!, node.label, Modifier.fillMaxWidth().heightIn(max = 320.dp))
                }
                UiDocumentMediaKind.AUDIO -> V6AudioPlayback(path)
                UiDocumentMediaKind.VIDEO -> V6VideoPlayback(path)
            }
        }
    }
}

/** Export is a user-granted SAF write; the owner rechecks the asset and session before copying. */
@Composable
private fun V6MediaExport(node: UiDocumentNode.Media, sessionId: String, media: MediaUiPort,
    localPath: String) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var exporting by remember(node.assetId, sessionId) { mutableStateOf(false) }
    var result by remember(node.assetId, sessionId) { mutableStateOf<String?>(null) }
    val mime = when (node.kind) {
        UiDocumentMediaKind.IMAGE -> "image/*"
        UiDocumentMediaKind.AUDIO -> "audio/*"
        UiDocumentMediaKind.VIDEO -> "video/*"
    }
    // The source file may be JPEG/WEBP, WAV or MOV. Do not relabel bytes as PNG/MP3/MP4.
    val extension = java.io.File(localPath).extension.lowercase().let { ext ->
        val supported = when (node.kind) {
            UiDocumentMediaKind.IMAGE -> setOf("jpg", "jpeg", "png", "webp", "gif")
            UiDocumentMediaKind.AUDIO -> setOf("mp3", "wav", "aac", "m4a", "ogg", "opus")
            UiDocumentMediaKind.VIDEO -> setOf("mp4", "mov", "webm", "mkv")
        }
        ext.takeIf { it in supported }
    }
    val filename = when (node.kind) {
        UiDocumentMediaKind.IMAGE -> "image"
        UiDocumentMediaKind.AUDIO -> "audio"
        UiDocumentMediaKind.VIDEO -> "video"
    } + "-" + node.assetId.take(24).replace(Regex("[^a-zA-Z0-9_-]"), "_") +
        (extension?.let { "." + it } ?: "")
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri == null) result = "已取消保存"
        else {
            exporting = true
            scope.launch {
                try {
                    val outcome = withContext(Dispatchers.IO) {
                        val output = resolver.openOutputStream(uri, "wt")
                        if (output == null) MediaUiReceipt.Failed("无法打开目标文档")
                        else output.use { media.exportToSessionAsset(node.assetId, sessionId, it) }
                    }
                    result = when (outcome) {
                        MediaUiReceipt.Applied -> "已保存到选择的位置"
                        is MediaUiReceipt.Failed -> outcome.message ?: "导出失败"
                        else -> "未完成导出"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { result = failure.message ?: "导出失败" }
                finally { exporting = false }
            }
        }
    }
    SyButton("保存到设备", onClick = {
        try { launcher.launch(filename) }
        catch (failure: Exception) { result = failure.message ?: "无法打开保存位置" }
    }, enabled = !exporting, style = SyButtonStyle.SECONDARY)
    result?.let { Text(it) }
}

@Composable
private fun V6AudioPlayback(path: String) {
    var retry by remember(path) { mutableIntStateOf(0) }
    var ready by remember(path, retry) { mutableStateOf(false) }
    var playing by remember(path, retry) { mutableStateOf(false) }
    var failed by remember(path, retry) { mutableStateOf(false) }
    val player = remember(path, retry) { MediaPlayer() }
    DisposableEffect(player) {
        player.setOnPreparedListener { ready = true }
        player.setOnCompletionListener { playing = false }
        player.setOnErrorListener { _, _, _ -> failed = true; ready = false; playing = false; true }
        try { player.setDataSource(path); player.prepareAsync() }
        catch (_: Exception) { failed = true }
        onDispose {
            runCatching { player.stop() }
            player.release()
        }
    }
    if (failed) {
        Text("音频无法播放")
        SyButton("重试", { retry++ }, style = SyButtonStyle.SECONDARY)
    } else {
        if (!ready) Text("准备音频中")
        SyButton(if (playing) "暂停" else "播放", onClick = {
            try {
                if (player.isPlaying) player.pause() else player.start()
                playing = player.isPlaying
            } catch (_: Exception) { failed = true; ready = false; playing = false }
        }, enabled = ready)
    }
}

@Composable
private fun V6VideoPlayback(path: String) {
    var video by remember(path) { mutableStateOf<VideoView?>(null) }
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoPath(path)
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                video = this
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
    )
    DisposableEffect(video) { onDispose { video?.stopPlayback() } }
    Text("点击视频播放器可开始或暂停")
}
