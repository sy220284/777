package com.labteto.dshmobile.ui.screens.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.model.LocalMediaResult
import com.labteto.dshmobile.local.model.LocalMediaVideoPhase
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat-native presentation of persisted media work. The attached result is NOT a text turn,
 * so a fictional persona/model never gets a fabricated speech message in its history.
 */
@Composable
internal fun LocalGeneratedMediaResultRow(
    result: LocalMediaResult,
    viewModel: LocalMediaStudioViewModel,
    onInsertText: (String) -> Unit,
) {
    val context = LocalContext.current
    val file = remember(result.id, result.fileName) { viewModel.resultFile(result) }
    var error by remember(result.id) { mutableStateOf<String?>(null) }
    val label = when (result.kind) {
        LocalMediaKind.IMAGE.name -> context.getString(R.string.media_chat_image_done)
        LocalMediaKind.SPEECH.name -> context.getString(R.string.media_chat_speech_done)
        LocalMediaKind.TRANSCRIBE.name -> context.getString(R.string.media_chat_transcript_done)
        else -> context.getString(R.string.media_chat_generic_result)
    }
    MediaMessageCard(title = label, subtitle = "${result.provider} · ${result.model}") {
        result.description?.takeIf(String::isNotBlank)?.let {
            Text(it, style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.labelSecondary, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
        }
        when (result.kind) {
            LocalMediaKind.IMAGE.name -> {
                MediaInlinePicture(file = file, remoteUrl = result.remoteUrl)
                Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    if (file != null) {
                        DsButton(text = context.getString(R.string.media_chat_view_image), size = DsButtonSize.Small,
                            onClick = { viewMediaFile(context, file, imageMime(file)) { error = it } })
                        DsButton(text = context.getString(R.string.media_chat_share_save), size = DsButtonSize.Small,
                            variant = DsButtonVariant.Ghost,
                            onClick = { shareMediaFile(context, file, imageMime(file)) { error = it } })
                    } else result.remoteUrl?.let { url ->
                        DsButton(text = context.getString(R.string.media_chat_view_image), size = DsButtonSize.Small,
                            onClick = { openMediaUrl(context, url) { error = it } })
                        DsButton(text = context.getString(R.string.media_chat_share_url), size = DsButtonSize.Small,
                            variant = DsButtonVariant.Ghost,
                            onClick = { shareMediaUrl(context, url) { error = it } })
                    }
                }
            }
            LocalMediaKind.SPEECH.name -> {
                if (file != null) {
                    InlineSpeechPlayer(file)
                    DsButton(text = context.getString(R.string.media_chat_share_audio), size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                        onClick = { shareMediaFile(context, file, "audio/mpeg") { error = it } })
                } else Text(context.getString(R.string.media_chat_audio_missing), color = DsTheme.colors.labelSecondary)
            }
            LocalMediaKind.TRANSCRIBE.name -> {
                result.textOutput?.let { text ->
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(text, style = DsType.std14.withReadingWeight(),
                            color = DsTheme.colors.labelPrimary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        DsButton(text = context.getString(R.string.media_chat_insert_text), size = DsButtonSize.Small,
                            onClick = { onInsertText(text) })
                        DsButton(text = context.getString(R.string.media_chat_copy), size = DsButtonSize.Small,
                            variant = DsButtonVariant.Ghost,
                            onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.media_chat_transcription), text))
                            })
                    }
                }
            }
        }
        error?.let { Text(it, style = DsType.caption11, color = DsTheme.colors.labelSecondary) }
    }
}

@Composable
internal fun LocalGeneratedVideoTaskRow(
    task: LocalMediaVideoTask,
    offers: List<LocalMediaOffer>,
    viewModel: LocalMediaStudioViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedVideo = remember(task.id, task.fileName) { viewModel.videoFile(task) }
    val offer = remember(task.offerId, task.routeFingerprint, offers) {
        offers.firstOrNull { task.belongsTo(it) }
    }
    var refreshing by remember(task.id) { mutableStateOf(false) }
    var showingPlayer by remember(task.id) { mutableStateOf(false) }
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    // Poll the original provider task while this conversation is visible. The runtime
    // never re-submits a billable video request; it only reads its persisted task ID.
    LaunchedEffect(task.id, task.phase, offer?.id) {
        if (offer != null && task.phase in setOf(
                LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING
            )) {
            var waitMillis = 15_000L
            while (true) {
                delay(waitMillis)
                try {
                    viewModel.refreshVideo(offer, task.id)
                    waitMillis = 15_000L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: context.getString(R.string.media_chat_poll_failure)
                    waitMillis = (waitMillis * 2).coerceAtMost(90_000L)
                }
            }
        }
    }
    val phase = when (task.phase) {
        LocalMediaVideoPhase.SUBMITTING -> context.getString(R.string.media_chat_submitting)
        LocalMediaVideoPhase.OUTCOME_UNKNOWN -> context.getString(R.string.media_chat_outcome_unknown)
        LocalMediaVideoPhase.QUEUED -> context.getString(R.string.media_chat_queued)
        LocalMediaVideoPhase.PROCESSING -> context.getString(R.string.media_chat_processing)
        LocalMediaVideoPhase.SUCCEEDED -> context.getString(R.string.media_chat_video_done)
        LocalMediaVideoPhase.FAILED -> context.getString(R.string.media_chat_generation_failed)
    }
    MediaMessageCard(title = phase, subtitle = "${task.provider} · ${task.model}") {
        task.description?.let {
            Text(it, style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.labelSecondary, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
        }
        if (task.phase == LocalMediaVideoPhase.SUCCEEDED && task.downloadUrl != null) {
            if (showingPlayer) {
                InlineVideoPlayer(task.downloadUrl, savedVideo, onError = { error = it })
            } else {
                Surface(shape = DsShapes.block, color = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    androidx.compose.foundation.layout.Box(
                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                        DsButton(context.getString(R.string.media_chat_play_video), onClick = { showingPlayer = true })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsButton(text = if (showingPlayer) context.getString(R.string.media_chat_collapse_video) else context.getString(R.string.media_chat_play_video), size = DsButtonSize.Small,
                    onClick = { showingPlayer = !showingPlayer })
                if (savedVideo != null) {
                    DsButton(text = context.getString(R.string.media_chat_share_video), size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                        onClick = { shareMediaFile(context, savedVideo, "video/mp4") { error = it } })
                } else {
                    DsButton(text = context.getString(R.string.media_chat_open_video), size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                        onClick = { openMediaUrl(context, task.downloadUrl) { error = it } })
                }
            }
        } else {
            task.lastError?.let {
                Text(it, style = DsType.caption11.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary)
            }
            if (task.phase in setOf(LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING)) {
                DsButton(text = if (refreshing) context.getString(R.string.media_chat_polling) else context.getString(R.string.media_chat_refresh_progress),
                    enabled = !refreshing && offer != null,
                    size = DsButtonSize.Small, onClick = {
                        val bound = offer ?: return@DsButton
                        scope.launch {
                            refreshing = true
                            error = null
                            try { viewModel.refreshVideo(bound, task.id) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: context.getString(R.string.media_chat_refresh_failed) }
                            finally { refreshing = false }
                        }
                    })
            } else if (task.phase == LocalMediaVideoPhase.OUTCOME_UNKNOWN) {
                Text(context.getString(R.string.media_chat_check_remote_task),
                    color = DsTheme.colors.labelSecondary, style = DsType.caption11.withReadingWeight())
            }
            if (offer == null && task.phase in setOf(LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING)) {
                Text(context.getString(R.string.media_chat_account_missing),
                    color = DsTheme.colors.labelSecondary, style = DsType.caption11.withReadingWeight())
            }
        }
        task.lastError?.takeIf { task.phase == LocalMediaVideoPhase.SUCCEEDED }?.let {
            Text(it, style = DsType.caption11.withReadingWeight(),
                color = DsTheme.colors.labelSecondary)
        }
        error?.let { Text(it, color = DsTheme.colors.labelSecondary,
            style = DsType.caption11.withReadingWeight()) }
    }
}

@Composable
private fun MediaMessageCard(
    title: String, subtitle: String, content: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(0.90f).widthIn(max = 470.dp),
        shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL3),
    ) {
        Column(Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text(title, style = DsType.small13Strong.withReadingWeight(), color = colors.labelPrimary)
            Text(subtitle, style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary)
            content()
        }
    }
}

@Composable
private fun MediaInlinePicture(file: File?, remoteUrl: String?) {
    val context = LocalContext.current
    var bitmap by remember(file?.absolutePath, remoteUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file?.absolutePath, remoteUrl) {
        bitmap = withContext(Dispatchers.IO) { decodeMediaPreview(file, remoteUrl) }
    }
    val modifier = Modifier.fillMaxWidth().aspectRatio(1.2f)
        .background(DsTheme.colors.bgLayer1, DsShapes.block)
        .border(1.dp, DsTheme.colors.borderL3, DsShapes.block)
    if (bitmap != null) {
        Image(bitmap = bitmap!!, contentDescription = context.getString(R.string.media_chat_generated_image),
            contentScale = ContentScale.Fit, modifier = modifier)
    } else {
        androidx.compose.foundation.layout.Box(modifier,
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(context.getString(R.string.media_chat_preview_pending), style = DsType.caption11.withReadingWeight(),
                color = DsTheme.colors.labelSecondary)
        }
    }
}

private fun imageMime(file: File): String = when (file.extension.lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    else -> "image/jpeg"
}

private fun decodeMediaPreview(file: File?, remoteUrl: String?): ImageBitmap? = runCatching {
    val bytes = when {
        file?.isFile == true -> {
            if (file.length() > 12_000_000L) return@runCatching null
            file.readBytes()
        }
        remoteUrl != null -> {
            val uri = URI(remoteUrl)
            require(uri.scheme == "https" && uri.host != null && uri.port == -1)
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false
                require(connection.responseCode == 200 && connection.contentType?.startsWith("image/") == true)
                require(connection.contentLengthLong in -1..12_000_000L)
                val output = ByteArrayOutputStream()
                connection.inputStream.use { stream ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val size = stream.read(chunk)
                        if (size < 0) break
                        require(output.size() + size <= 12_000_000)
                        output.write(chunk, 0, size)
                    }
                }
                output.toByteArray()
            } finally { connection.disconnect() }
        }
        else -> return@runCatching null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

@Composable
private fun InlineSpeechPlayer(file: File) {
    val context = LocalContext.current
    var playing by remember(file.absolutePath) { mutableStateOf(false) }
    var error by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var player by remember(file.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(file.absolutePath) {
        onDispose { player?.release(); player = null }
    }
    DsButton(text = if (playing) context.getString(R.string.media_chat_pause_audio) else context.getString(R.string.media_chat_play_audio), size = DsButtonSize.Small,
        onClick = {
            runCatching {
                val current = player ?: MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    setOnCompletionListener { playing = false }
                }.also { player = it }
                if (playing) current.pause() else current.start()
                playing = !playing
            }.onFailure { error = it.message ?: context.getString(R.string.media_chat_audio_failed) }
        })
    error?.let { Text(it, style = DsType.caption11.withReadingWeight(),
        color = DsTheme.colors.labelSecondary) }
}

@Composable
private fun InlineVideoPlayer(url: String, localFile: File?, onError: (String) -> Unit) {
    val context = LocalContext.current
    var player by remember(url, localFile?.absolutePath) { mutableStateOf<VideoView?>(null) }
    DisposableEffect(url, localFile?.absolutePath) {
        onDispose { player?.stopPlayback(); player = null }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        factory = { context ->
            VideoView(context).apply {
                val controls = MediaController(context)
                controls.setAnchorView(this)
                setMediaController(controls)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ -> onError(context.getString(R.string.media_chat_video_failed)); true }
                setVideoURI(localFile?.let { Uri.fromFile(it) } ?: Uri.parse(url))
                player = this
            }
        },
    )
}

private fun shareMediaUrl(context: Context, url: String, onError: (String) -> Unit) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, url)
        }
        context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.media_chat_share_image_link)))
    }.onFailure { onError(it.message.orEmpty()) }
}

/** Inline Work delivery: preview first, workspace/file browser is only an optional detail view. */
@Composable
internal fun LocalWorkArtifactMessageRow(
    artifact: LocalArtifactUiItem,
    workspacePath: String,
    actions: LocalWorkFeatureUiActions?,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(workspacePath, artifact.reference, artifact.versionNow) {
        runCatching {
            require(workspacePath.isNotBlank())
            val root = File(workspacePath).canonicalFile
            File(root, artifact.reference).canonicalFile.takeIf {
                it.toPath().startsWith(root.toPath()) && it.isFile
            }
        }.getOrNull()
    }
    var preview by remember(artifact.reference, artifact.versionNow) {
        mutableStateOf<LocalWorkspaceFilePreview?>(null)
    }
    var expanded by remember(artifact.reference) { mutableStateOf(false) }
    var error by remember(artifact.reference) { mutableStateOf<String?>(null) }
    LaunchedEffect(artifact.reference, artifact.versionNow, artifact.currentlyAvailable, actions) {
        preview = null
        if (artifact.category == "file" && artifact.currentlyAvailable != false && actions != null) {
            try {
                preview = withContext(Dispatchers.IO) {
                    actions.previewWorkspaceFile(artifact.reference)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: context.getString(R.string.media_chat_preview_failed)
            }
        }
    }
    val isImage = artifact.category == "file" &&
        artifact.reference.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")
    val name = artifact.reference.substringAfterLast('/').ifBlank { artifact.reference }
    MediaMessageCard(
        title = if (artifact.category == "file") context.getString(R.string.media_chat_work_file, name) else context.getString(R.string.media_chat_work_result),
        subtitle = if (artifact.currentlyAvailable == false) context.getString(R.string.media_chat_work_file_missing) else context.getString(R.string.media_chat_work_output_here),
    ) {
        if (isImage && file != null) {
            MediaInlinePicture(file, null)
        } else if (!preview?.text.isNullOrBlank()) {
            val text = preview!!.text!!
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(if (expanded) text.take(16_000) else text.take(2500),
                    style = DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont),
                    color = DsTheme.colors.labelPrimary)
            }
            if (text.length > 2500) {
                DsButton(
                    text = if (expanded) context.getString(R.string.media_chat_collapse_preview) else context.getString(R.string.media_chat_expand_preview),
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                    onClick = { expanded = !expanded },
                )
            }
        } else if (artifact.category == "file") {
            Text(if (file != null) context.getString(R.string.media_chat_work_file_ready)
                else context.getString(R.string.media_chat_work_no_preview),
                style = DsType.caption11.withReadingWeight(), color = DsTheme.colors.labelSecondary)
        } else {
            Text(artifact.reference.take(160),
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.labelPrimary)
        }
        if (artifact.versionAtCreation != null && artifact.versionNow != null &&
            artifact.versionAtCreation != artifact.versionNow) {
            Text(context.getString(R.string.media_chat_work_file_changed), style = DsType.caption11.withReadingWeight(),
                color = DsTheme.colors.labelSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            if (artifact.category == "file") {
                DsButton(context.getString(R.string.media_chat_view_entire_file), onClick = onOpen, size = DsButtonSize.Small)
                if (file != null) {
                    DsButton(context.getString(R.string.media_chat_share_save), size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                        onClick = {
                            shareMediaFile(context, file, localArtifactMime(file)) { error = it }
                        })
                }
            } else if (artifact.reference.startsWith("https://")) {
                DsButton(context.getString(R.string.media_chat_open_result), size = DsButtonSize.Small,
                    onClick = { openMediaUrl(context, artifact.reference) { error = it } })
            }
        }
        error?.let { Text(it, style = DsType.caption11.withReadingWeight(),
            color = DsTheme.colors.labelSecondary) }
    }
}

private fun localArtifactMime(file: File): String = when (file.extension.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "md", "txt", "log", "kt", "py", "js", "ts", "json" -> "text/plain"
    "pdf" -> "application/pdf"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
