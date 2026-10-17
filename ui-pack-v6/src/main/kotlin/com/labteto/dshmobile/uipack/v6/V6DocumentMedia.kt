package com.labteto.dshmobile.uipack.v6

import android.graphics.BitmapFactory
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.model.LocalMediaPage
import com.labteto.dshmobile.uihost.rememberUiStreamDocumentSaver
import com.labteto.dshmobile.uihost.UiDocumentWriteReceipt
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.MediaUiReceipt
import com.labteto.dshmobile.uicontract.UiDocumentMediaKind
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiMediaAsset
import com.shenyan.designsystem.components.SyEntrance
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import com.shenyan.designsystem.foundation.SyMotion
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
    var assetRefresh by remember(node.assetId, sessionId) { mutableIntStateOf(0) }
    val asset = remember(media, sessionId, node.assetId, node.kind, page, assetRefresh) {
        resolveV6DocumentMediaAsset(media, node, sessionId)
    }
    val path = asset?.localPath
    val scope = rememberCoroutineScope()
    var restoring by remember(node.assetId, sessionId) { mutableStateOf(false) }
    var restoreNotice by remember(node.assetId, sessionId) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(node.label)
        if (path == null || media == null) {
            Text("此会话的媒体文件尚未保存在设备上，或当前访问未获授权。")
            if (media != null && asset?.remoteUrl != null && node.kind == UiDocumentMediaKind.IMAGE) {
                SyButton(if (restoring) "正在恢复原图片…" else "重新下载已生成图片（不重新生成）", {
                    if (!restoring) {
                        restoring = true
                        scope.launch {
                            try {
                                restoreNotice = when (val receipt = withContext(Dispatchers.IO) {
                                    media.retrySessionImageDownload(node.assetId, sessionId)
                                }) {
                                    MediaUiReceipt.Applied -> { assetRefresh++; "原图片已重新保存到本地" }
                                    is MediaUiReceipt.Failed -> receipt.message ?: "原图片下载失败"
                                    else -> "图片恢复结果未知，请刷新后查看原任务记录"
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { restoreNotice = failure.message ?: "原图片下载失败" }
                            finally { restoring = false }
                        }
                    }
                }, enabled = !restoring, style = SyButtonStyle.SECONDARY)
                restoreNotice?.let { Text(it) }
            }
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
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        val image = bitmap
                        if (image == null) Text("图片载入中或无法解码")
                        else SyEntrance("media:$sessionId:${node.assetId}:$path", durationMs = SyMotion.portraitRevealMs) {
                            Image(image, node.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    }
                }
                UiDocumentMediaKind.AUDIO -> com.labteto.dshmobile.uihost.UiOwnedMediaPlayback(
                    path, com.labteto.dshmobile.uicontract.UiAttachmentMediaKind.AUDIO)
                UiDocumentMediaKind.VIDEO -> com.labteto.dshmobile.uihost.UiOwnedMediaPlayback(
                    path, com.labteto.dshmobile.uicontract.UiAttachmentMediaKind.VIDEO)
            }
        }
    }
}

/** Export is a user-granted SAF write; the owner rechecks the asset and session before copying. */
@Composable
private fun V6MediaExport(node: UiDocumentNode.Media, sessionId: String, media: MediaUiPort,
    localPath: String) {
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
    val save = rememberUiStreamDocumentSaver(
        mimeType = mime,
        filename = { _: Pair<String, String> -> filename },
        write = { request, output ->
            when (val receipt = media.exportToSessionAsset(request.first, request.second, output)) {
                MediaUiReceipt.Applied -> UiDocumentWriteReceipt.Saved
                is MediaUiReceipt.Failed -> UiDocumentWriteReceipt.Failed(receipt.message)
                else -> UiDocumentWriteReceipt.Failed("未完成导出")
            }
        },
        onRejected = { receipt ->
            exporting = false
            result = receipt.message
        },
        onResult = { request, receipt ->
            if (request.first == node.assetId && request.second == sessionId) {
                exporting = false
                result = when (receipt) {
                    UiDocumentWriteReceipt.Saved -> "已保存到选择的位置"
                    UiDocumentWriteReceipt.Cancelled -> "已取消保存"
                    is UiDocumentWriteReceipt.Failed -> receipt.message ?: "导出失败"
                }
            }
        },
    )
    SyButton("保存到设备", onClick = {
        exporting = true
        save(node.assetId to sessionId)
    }, enabled = !exporting, style = SyButtonStyle.SECONDARY)
    result?.let { Text(it) }
}
