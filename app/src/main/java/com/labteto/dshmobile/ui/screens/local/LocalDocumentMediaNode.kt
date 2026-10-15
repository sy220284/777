package com.labteto.dshmobile.ui.screens.local

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.MediaUiReceipt
import com.labteto.dshmobile.uicontract.UiDocumentMediaKind
import com.labteto.dshmobile.uicontract.UiDocumentNode
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** V6 resolves the same Media Owner as Next; never trust file paths from document nodes. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface LocalDocumentMediaEntryPoint {
    fun mediaUiPort(): MediaUiPort
}

/**
 * Only media belonging to the document session may render. Remote-only media is not
 * downloaded automatically; export always requires the user's SAF destination choice.
 */
@Composable
internal fun LocalDocumentMediaNode(node: UiDocumentNode.Media, sessionId: String) {
    val context = LocalContext.current
    val media = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext, LocalDocumentMediaEntryPoint::class.java
        ).mediaUiPort()
    }
    val asset = media.assetForSession(node.assetId, sessionId)?.takeIf { it.kind == node.kind }
    val localPath = asset?.localPath
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(node.label)
        if (localPath == null) {
            Text(stringResource(R.string.ui_document_media_unavailable))
        } else {
            when (node.kind) {
                UiDocumentMediaKind.IMAGE -> LocalDocumentMediaImage(localPath, node.label)
                UiDocumentMediaKind.AUDIO -> LocalDocumentMediaAudio(localPath)
                UiDocumentMediaKind.VIDEO -> LocalDocumentMediaVideo(localPath)
            }
            LocalDocumentMediaExport(media, node, sessionId)
        }
    }
}

@Composable
private fun LocalDocumentMediaImage(path: String, description: String) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
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
    val decoded = image
    if (decoded == null) Text(stringResource(R.string.ui_document_media_loading))
    else Image(decoded, contentDescription = description,
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
}

@Composable
private fun LocalDocumentMediaAudio(path: String) {
    val player = remember(path) { MediaPlayer() }
    var ready by remember(path) { mutableStateOf(false) }
    var playing by remember(path) { mutableStateOf(false) }
    DisposableEffect(path) {
        try {
            player.setDataSource(path)
            player.prepare()
            player.setOnCompletionListener { playing = false }
            ready = true
        } catch (_: Exception) {
            ready = false
        }
        onDispose { player.release() }
    }
    if (!ready) Text(stringResource(R.string.ui_document_media_playback_failed))
    SyButton(
        text = stringResource(if (playing) R.string.ui_document_media_pause else R.string.ui_document_media_play),
        enabled = ready,
        variant = SyButtonVariant.Ghost,
        onClick = {
            try {
                if (player.isPlaying) player.pause() else player.start()
                playing = player.isPlaying
            } catch (_: Exception) {
                ready = false
                playing = false
            }
        }
    )
}

@Composable
private fun LocalDocumentMediaVideo(path: String) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoPath(path)
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 330.dp),
        update = { view -> if (view.tag != path) {
            view.setVideoPath(path)
            view.tag = path
        } },
    )
    Text(stringResource(R.string.ui_document_media_video_hint))
}

/** The Media Owner checks session scope a second time immediately before copying bytes. */
@Composable
private fun LocalDocumentMediaExport(media: MediaUiPort, node: UiDocumentNode.Media, sessionId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember(node.assetId, sessionId) { mutableStateOf<String?>(null) }
    val mime = when (node.kind) {
        UiDocumentMediaKind.IMAGE -> "image/*"
        UiDocumentMediaKind.AUDIO -> "audio/*"
        UiDocumentMediaKind.VIDEO -> "video/*"
    }
    val suffix = when (node.kind) {
        UiDocumentMediaKind.IMAGE -> ".png"
        UiDocumentMediaKind.AUDIO -> ".mp3"
        UiDocumentMediaKind.VIDEO -> ".mp4"
    }
    val cancelled = stringResource(R.string.ui_document_media_export_cancelled)
    val exporting = stringResource(R.string.ui_document_media_exporting)
    val exported = stringResource(R.string.ui_document_media_exported)
    val failed = stringResource(R.string.ui_document_media_export_failed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri == null) result = cancelled
        else scope.launch {
            result = exporting
            try {
                val receipt = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        media.exportToSessionAsset(node.assetId, sessionId, stream)
                    } ?: MediaUiReceipt.Failed(failed)
                }
                result = when (receipt) {
                    MediaUiReceipt.Applied -> exported
                    is MediaUiReceipt.Failed -> receipt.message?.takeIf(String::isNotBlank) ?: failed
                    else -> failed
                }
            } catch (cancelledException: CancellationException) {
                throw cancelledException
            } catch (failure: Exception) {
                result = failure.message?.takeIf(String::isNotBlank) ?: failed
            }
        }
    }
    SyButton(
        text = stringResource(R.string.ui_document_media_export),
        variant = SyButtonVariant.Ghost,
        onClick = { launcher.launch("media-${node.assetId.take(24)}$suffix") }
    )
    result?.let { Text(it) }
}
