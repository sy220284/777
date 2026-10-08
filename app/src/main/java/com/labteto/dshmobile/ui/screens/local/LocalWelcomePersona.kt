package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.content.res.AssetManager
import android.graphics.ImageDecoder
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/** The saved gallery list remains independent of the showcase's five dedicated motion artworks. */
internal data class WelcomePersonaArtwork(
    val id: String,
    val name: String,
    val portraitPath: String = "",
    val presetAssetPath: String = "",
)

internal fun welcomePersonaArtworks(
    gallery: List<PersonaGalleryEntry>,
    presets: List<PersonaPreset>,
): List<WelcomePersonaArtwork> {
    val saved = gallery.filter { it.portraitPath.isNotBlank() }.map { entry ->
        WelcomePersonaArtwork(
            id = "saved:${entry.id}",
            name = entry.persona.name,
            portraitPath = entry.portraitPath,
        )
    }
    val savedPresetIds = gallery.asSequence()
        .filter { it.portraitPath.isNotBlank() }
        .map { it.persona.presetId }
        .filter(String::isNotBlank)
        .toSet()
    val bundled = presets.mapNotNull { preset ->
        preset.artwork?.assetPath?.takeIf(String::isNotBlank)
            ?.takeUnless { preset.id in savedPresetIds }
            ?.let { path ->
                WelcomePersonaArtwork(
                    id = "preset:${preset.id}",
                    name = preset.persona.name,
                    presetAssetPath = path,
                )
            }
    }
    return saved + bundled
}

internal fun nextWelcomePersonaIndex(current: Int, total: Int): Int =
    if (total <= 1) 0 else (current.coerceIn(0, total - 1) + 1) % total

internal data class WelcomeMotionPersona(
    val id: String,
    val name: String,
    val assetPath: String,
    val accent: Color,
    val highlight: Color,
)

/** Custom-made holographic illustrations, never the standard preset gallery paintings. */
internal val welcomeMotionPersonas: List<WelcomeMotionPersona> = listOf(
    WelcomeMotionPersona("ayaka", "神里绫华", "persona-motion/ayaka.webp", Color(0xFF86D7FF), Color(0xFFB5A6FF)),
    WelcomeMotionPersona("march7", "三月七", "persona-motion/march7.webp", Color(0xFFFFA8E7), Color(0xFF8ACBFF)),
    WelcomeMotionPersona("shenxinghui", "沈星回", "persona-motion/shenxinghui.webp", Color(0xFFF6DFAD), Color(0xFF94BEFF)),
    WelcomeMotionPersona("xiayizhou", "夏以昼", "persona-motion/xiayizhou.webp", Color(0xFFFF957B), Color(0xFFFFC184)),
    WelcomeMotionPersona("klee", "可莉", "persona-motion/klee.webp", Color(0xFFFFA06C), Color(0xFFFFE1A0)),
)

/** Showcase only: tapping does not bind a chat persona or send a message. */
@Composable
internal fun WelcomePersonaCarousel(@Suppress("UNUSED_PARAMETER") gallery: List<PersonaGalleryEntry>) {
    var index by rememberSaveable { mutableStateOf(0) }
    val selection = welcomeMotionPersonas[index.coerceIn(0, welcomeMotionPersonas.lastIndex)]
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val powerSave = remember(context) {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    }
    val motionEnabled = resumed && !reducedMotion && !powerSave
    val breathing = remember { Animatable(0.4f) }
    val orbit = remember { Animatable(0f) }
    LaunchedEffect(motionEnabled) {
        if (motionEnabled) {
            launch {
                while (isActive) {
                    breathing.animateTo(1f, tween(3600))
                    breathing.animateTo(0f, tween(3600))
                }
            }
            launch {
                while (isActive) {
                    orbit.snapTo(0f)
                    orbit.animateTo(1f, tween(10500, easing = LinearEasing))
                }
            }
        } else {
            breathing.snapTo(0.4f)
            orbit.snapTo(0f)
        }
    }
    val scan = remember { Animatable(1.25f) }
    LaunchedEffect(selection.id, motionEnabled) {
        if (motionEnabled) {
            scan.snapTo(-0.20f)
            scan.animateTo(1.25f, animationSpec = tween(1050, easing = LinearEasing))
        } else {
            scan.snapTo(1.25f)
        }
    }
    val label = stringResource(R.string.local_welcome_persona_switch, selection.name)
    val radius = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .width(252.dp)
            .height(328.dp)
            .clip(radius)
            .background(Color(0xFF101629))
            .clickable(role = Role.Button) {
                index = nextWelcomePersonaIndex(index, welcomeMotionPersonas.size)
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = selection, animationSpec = tween(if (reducedMotion) 0 else 440), label = "welcome-persona-hologram") { persona ->
            val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = persona.assetPath) {
                value = withContext(Dispatchers.IO) {
                    decodeWelcomeMotionArtwork(context.assets, persona.assetPath)
                }
            }
            if (bitmap == null) {
                Icon(
                    imageVector = FeatherIcons.User,
                    contentDescription = null,
                    tint = persona.accent,
                    modifier = Modifier.width(64.dp).height(64.dp),
                )
            } else {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        if (motionEnabled) {
                            translationY = (breathing.value - 0.5f) * 4f
                            scaleX = 1.025f + breathing.value * 0.006f
                            scaleY = 1.025f + breathing.value * 0.006f
                        }
                    },
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val accent = selection.accent
            val highlight = selection.highlight
            val w = size.width
            val h = size.height
            val pulse = if (motionEnabled) breathing.value else 0.4f
            val movement = if (motionEnabled) orbit.value else 0f
            drawArc(
                color = accent.copy(alpha = 0.35f + pulse * 0.16f),
                startAngle = -42f + movement * 120f,
                sweepAngle = 126f,
                useCenter = false,
                topLeft = Offset(w * 0.055f, h * 0.17f),
                size = Size(w * 0.89f, h * 0.58f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawArc(
                color = highlight.copy(alpha = 0.28f),
                startAngle = 152f - movement * 90f,
                sweepAngle = 142f,
                useCenter = false,
                topLeft = Offset(w * 0.10f, h * 0.11f),
                size = Size(w * 0.8f, h * 0.71f),
                style = Stroke(width = 1.dp.toPx()),
            )
            // Sparse stable particles, without allocating objects or images per frame.
            for (i in 0 until 27) {
                val seedX = ((i * 71 + 19) % 97) / 97f
                val seedY = ((i * 41 + 7) % 101) / 101f
                val drift = if (motionEnabled) sin((movement * 2 * PI + i * 1.7)).toFloat() * 4.dp.toPx() else 0f
                drawCircle(
                    color = if (i % 3 == 0) highlight.copy(alpha = 0.6f) else accent.copy(alpha = 0.36f),
                    radius = if (i % 5 == 0) 1.65.dp.toPx() else 0.75.dp.toPx(),
                    center = Offset(seedX * w, seedY * h + drift),
                )
            }
            val progress = scan.value
            if (progress in 0f..1f) {
                val y = progress * h
                drawLine(accent.copy(alpha = 0.8f), Offset(0f, y), Offset(w, y), strokeWidth = 2.dp.toPx())
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, accent.copy(alpha = 0.19f), Color.Transparent),
                        startY = y - 28.dp.toPx(), endY = y + 18.dp.toPx(),
                    ),
                    topLeft = Offset(0f, (y - 28.dp.toPx()).coerceAtLeast(0f)),
                    size = Size(w, 46.dp.toPx()),
                )
            }
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.63f to Color.Transparent,
                    1f to Color(0xE5101220),
                ),
            )
            drawRoundRect(
                brush = Brush.linearGradient(listOf(accent.copy(alpha = 0.66f), highlight.copy(alpha = 0.42f))),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(26.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(selection.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.local_welcome_persona_hint), color = selection.accent.copy(alpha = 0.94f), fontSize = 11.sp)
        }
    }
}

private fun decodeWelcomeMotionArtwork(assets: AssetManager, path: String): ImageBitmap? {
    if (welcomeMotionPersonas.none { it.assetPath == path }) return null
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            val longest = maxOf(info.size.width, info.size.height)
            while (longest / sample > 1200) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }.asImageBitmap()
    }.getOrNull()
}