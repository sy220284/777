package com.labteto.dshmobile.uihost

import android.net.Uri
import android.graphics.ImageDecoder
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.VideoView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.labteto.dshmobile.uicontract.UiAttachmentMedia
import com.labteto.dshmobile.uicontract.UiAttachmentMediaKind

/**
 * Android-native decoder/player for media explicitly admitted by Attachment/File Owner.
 * The opaque content URI is app-private; the Host never reads arbitrary model-supplied paths.
 * Each player is released as soon as its preview closes or the inspected attachment changes.
 */
@Composable
fun UiAttachmentMediaViewer(media: UiAttachmentMedia, modifier: Modifier = Modifier) {
    require(Uri.parse(media.contentUri).scheme == "content") {
        "附件播放器只接受经原 Owner 颁发的 content URI"
    }
    val context = LocalContext.current
    val uri = remember(media.contentUri) { Uri.parse(media.contentUri) }
    val view = remember(context, media) {
        when (media.kind) {
            UiAttachmentMediaKind.IMAGE ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val image = ImageView(context).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "导入图片预览"
                    }
                    addView(image)
                    addView(TextView(context).apply { text = "正在读取图片" })
                    tag = image
                }
            UiAttachmentMediaKind.AUDIO, UiAttachmentMediaKind.VIDEO ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val player = VideoView(context).apply {
                        setVideoURI(uri)
                        setMediaController(MediaController(context).also { controls ->
                            controls.setAnchorView(this)
                        })
                    }
                    val status = TextView(context)
                    player.setOnErrorListener { _, _, _ ->
                        status.text = "无法解码或读取此媒体，请重新检查源文件；不会重新生成或计费"
                        true
                    }
                    player.setOnPreparedListener { status.text = "媒体已就绪" }
                    addView(status)
                    addView(player, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        if (media.kind == UiAttachmentMediaKind.AUDIO) 60 else 400,
                    ))
                    val controls = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(Button(context).apply {
                            text = "播放"
                            setOnClickListener { player.start() }
                        })
                        addView(Button(context).apply {
                            text = "暂停"
                            setOnClickListener { if (player.isPlaying) player.pause() }
                        })
                        addView(Button(context).apply {
                            text = "重新读取"
                            setOnClickListener { status.text = "正在读取媒体"; player.setVideoURI(uri) }
                        })
                    }
                    addView(controls)
                    tag = player
                }
        }
    }
    val image = (view as? LinearLayout)?.tag as? ImageView
    if (image != null) LaunchedEffect(view, uri) {
        val status = view.getChildAt(1) as TextView
        try {
            val bitmap = withContext(Dispatchers.IO) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    var sample = 1
                    while (maxOf(info.size.width, info.size.height) / sample > 1600) sample *= 2
                    decoder.setTargetSampleSize(sample)
                }
            }
            image.setImageBitmap(bitmap)
            status.text = ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status.text = "无法读取或解码此图片，请重新选择原文件" }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                ((view as? LinearLayout)?.tag as? VideoView)?.let { player ->
                    if (player.isPlaying) player.pause()
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(view) {
        onDispose { (view as? LinearLayout)?.tag?.let { (it as? VideoView)?.stopPlayback() } }
    }
    key(media) { AndroidView(factory = { view }, modifier = modifier) }
}

/** Local paths come only from a session-fenced Media Owner, never document/model text. */
@Composable
fun UiOwnedMediaPlayback(localPath: String, kind: UiAttachmentMediaKind, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val media = remember(context, localPath, kind) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(localPath))
        UiAttachmentMedia(kind, uri.toString())
    }
    UiAttachmentMediaViewer(media, modifier)
}
