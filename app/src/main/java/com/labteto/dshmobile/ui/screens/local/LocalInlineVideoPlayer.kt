package com.labteto.dshmobile.ui.screens.local

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaPlayer
import android.net.Uri
import android.view.Window
import android.widget.VideoView
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyBannerKind
import com.shenyan.designsystem.components.SyInlineBanner
import com.shenyan.designsystem.components.SyMediaTransport
import com.shenyan.designsystem.foundation.SyMetric
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private tailrec fun Context.videoWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.videoWindow()
    else -> null
}

/** Multiple expanded videos can share one activity window. Restore the previous owner on release. */
private object VideoWindowBrightness {
    private data class State(val original: Float, val owners: LinkedHashMap<Any, Float> = linkedMapOf())
    private val windows = mutableMapOf<Window, State>()

    fun adjust(window: Window, owner: Any, delta: Float): Float {
        val state = windows.getOrPut(window) { State(window.attributes.screenBrightness) }
        val starting = state.owners.remove(owner) ?: window.attributes.screenBrightness
        val level = ((starting.takeIf { it >= 0f } ?: .5f) + delta).coerceIn(.05f, 1f)
        state.owners[owner] = level
        window.attributes = window.attributes.apply { screenBrightness = level }
        return level
    }

    fun release(window: Window, owner: Any) {
        val state = windows[window] ?: return
        if (state.owners.remove(owner) == null) return
        val level = state.owners.values.lastOrNull() ?: state.original
        window.attributes = window.attributes.apply { screenBrightness = level }
        if (state.owners.isEmpty()) windows.remove(window)
    }
}

internal fun seekVideoBy(position: Int, duration: Int, forward: Boolean): Int =
    (position.toLong() + if (forward) 10_000 else -10_000)
        .coerceIn(0L, duration.coerceAtLeast(0).toLong()).toInt()

@Composable
internal fun InlineVideoPlayer(
    url: String?,
    localFile: File?,
    onError: (String) -> Unit,
    onCollapse: () -> Unit,
) {
    val source = localFile?.takeIf { it.isFile }?.let(Uri::fromFile)
        ?: url?.takeIf(String::isNotBlank)?.let(Uri::parse)
    val errorMessage = stringResource(R.string.media_chat_video_failed)
    if (source == null) {
        SyInlineBanner(errorMessage, SyBannerKind.ERROR)
        return
    }
    key(source) {
        val window = LocalContext.current.videoWindow()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val reportError by rememberUpdatedState(onError)
        var player by remember { mutableStateOf<VideoView?>(null) }
        var engine by remember { mutableStateOf<MediaPlayer?>(null) }
        var duration by remember { mutableStateOf(0) }
        var position by remember { mutableStateOf(0) }
        var playing by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var volume by remember { mutableStateOf(1f) }
        var gestureLabel by remember { mutableStateOf<String?>(null) }
        val brightnessOwner = remember(window) { Any() }

        fun seek(next: Int) {
            if (duration <= 0) return
            position = next.coerceIn(0, duration)
            player?.seekTo(position)
        }
        fun toggle() {
            val view = player ?: return
            if (engine == null || failed) return
            if (playing) view.pause() else {
                if (duration > 0 && position >= duration) seek(0)
                view.start()
            }
            playing = !playing
        }

        fun adjustVolume(delta: Float) {
            volume = (volume + delta).coerceIn(0f, 1f)
            engine?.setVolume(volume, volume)
            gestureLabel = "音量 ${(volume * 100f).roundToInt()}%"
        }
        fun adjustBrightness(delta: Float) {
            val videoWindow = window ?: return
            val brightness = VideoWindowBrightness.adjust(videoWindow, brightnessOwner, delta)
            gestureLabel = "亮度 ${(brightness * 100f).roundToInt()}%"
        }

        DisposableEffect(lifecycle, window) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    player?.pause()
                    playing = false
                    window?.let { VideoWindowBrightness.release(it, brightnessOwner) }
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                player?.stopPlayback()
                window?.let { VideoWindowBrightness.release(it, brightnessOwner) }
            }
        }
        LaunchedEffect(playing) {
            while (playing) {
                position = player?.currentPosition?.coerceAtLeast(0) ?: position
                delay(250)
            }
        }
        LaunchedEffect(gestureLabel) {
            if (gestureLabel != null) {
                delay(900)
                gestureLabel = null
            }
        }

        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        VideoView(context).apply {
                            player = this
                            setOnPreparedListener { media ->
                                engine = media
                                duration = media.duration.coerceAtLeast(0)
                                media.setVolume(volume, volume)
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    start()
                                    playing = true
                                }
                            }
                            setOnCompletionListener {
                                playing = false
                                position = duration
                            }
                            setOnErrorListener { _, _, _ ->
                                playing = false
                                failed = true
                                engine = null
                                reportError(errorMessage)
                                true
                            }
                            setVideoURI(source)
                        }
                    },
                )
                if (engine != null) {
                    val currentSeek by rememberUpdatedState<(Boolean) -> Unit> {
                        seek(seekVideoBy(position, duration, it))
                        gestureLabel = if (it) "前进 10 秒" else "后退 10 秒"
                    }
                    val setVolume by rememberUpdatedState<(Float) -> Unit>(::adjustVolume)
                    val setBrightness by rememberUpdatedState<(Float) -> Unit>(::adjustBrightness)
                    Box(
                        Modifier.fillMaxSize().testTag("sy-video-gesture-region")
                            .semantics {
                                contentDescription = "视频画面"
                                customActions = buildList {
                                    add(CustomAccessibilityAction("提高视频音量") { setVolume(.1f); true })
                                    add(CustomAccessibilityAction("降低视频音量") { setVolume(-.1f); true })
                                    if (window != null) {
                                        add(CustomAccessibilityAction("提高视频亮度") { setBrightness(.1f); true })
                                        add(CustomAccessibilityAction("降低视频亮度") { setBrightness(-.1f); true })
                                    }
                                }
                            }
                            .pointerInput(source) {
                                detectTapGestures(onDoubleTap = { currentSeek(it.x >= size.width / 2f) })
                            },
                    ) {
                        if (window != null) {
                            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(.30f).fillMaxHeight()
                                .pointerInput(source, window) {
                                    detectVerticalDragGestures { change, delta ->
                                        change.consume()
                                        setBrightness(-delta / size.height)
                                    }
                                })
                        }
                        Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(.30f).fillMaxHeight()
                            .pointerInput(source) {
                                detectVerticalDragGestures { change, delta ->
                                    change.consume()
                                    setVolume(-delta / size.height)
                                }
                            })
                    }
                }
                gestureLabel?.let { label ->
                    SyInlineBanner(label, SyBannerKind.INFO,
                        modifier = Modifier.align(Alignment.TopCenter).padding(SyMetric.gapSm))
                }
            }
            if (failed) SyInlineBanner(errorMessage, SyBannerKind.ERROR)
            SyMediaTransport(
                title = "视频播放", playing = playing,
                progress = if (duration > 0) position.toFloat() / duration else 0f,
                elapsedLabel = videoTimestamp(position),
                durationLabel = if (duration > 0) videoTimestamp(duration) else "—",
                onPlayPause = ::toggle,
                onSeek = { seek((it * duration).roundToInt()) },
                enabled = engine != null && !failed,
                seekable = duration > 0 && !failed,
                onCollapse = onCollapse,
                onSeekBackward = { seek(seekVideoBy(position, duration, false)) },
                onSeekForward = { seek(seekVideoBy(position, duration, true)) },
            )
        }
    }
}

private fun videoTimestamp(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
