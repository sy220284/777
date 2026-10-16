package com.labteto.dshmobile.uihost

import android.net.Uri
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.VideoView
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
                ImageView(context).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageURI(uri)
                    contentDescription = "导入图片预览"
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
                    }
                    addView(controls)
                    tag = player
                }
        }
    }
    DisposableEffect(view) {
        onDispose { (view as? LinearLayout)?.tag?.let { (it as VideoView).stopPlayback() } }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
