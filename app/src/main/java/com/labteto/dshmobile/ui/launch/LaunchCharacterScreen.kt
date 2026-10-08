package com.labteto.dshmobile.ui.launch

import android.content.res.AssetManager
import android.graphics.ImageDecoder
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.artwork.HologramCharacterArtwork
import com.labteto.dshmobile.ui.artwork.hologramCharacterArtworks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Show only for a normal cold launcher entry, never for notification/deep-link routing. */
internal fun shouldShowLaunchArtwork(
    restored: Boolean,
    action: String?,
    hasLauncherCategory: Boolean,
    hasSessionRequest: Boolean,
): Boolean = !restored && !hasSessionRequest &&
    action == "android.intent.action.MAIN" && hasLauncherCategory

internal fun nextLaunchArtworkIndex(index: Int, count: Int): Int =
    if (count <= 1) 0 else ((index.coerceIn(0, count - 1) + 1) % count)

/** Full-screen visual follows Android's required system icon splash; it never blocks app loading. */
@Composable
internal fun LaunchCharacterScreen(
    artwork: HologramCharacterArtwork,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = artwork.launchAssetPath) {
        value = withContext(Dispatchers.IO) { decodeLaunchArtwork(context.assets, artwork.launchAssetPath) }
    }
    val appearing = remember { Animatable(0f) }
    val exiting = remember { Animatable(1f) }
    val scan = remember { Animatable(-0.15f) }
    val scale = remember { Animatable(1.035f) }
    LaunchedEffect(artwork.id, reduceMotion) {
        if (reduceMotion) {
            // Still show a single complete frame, but respect the system's motion preference.
            appearing.snapTo(1f)
            scale.snapTo(1f)
            delay(350)
            onFinished()
        } else {
            launch { appearing.animateTo(1f, tween(390)) }
            launch { scan.animateTo(1.15f, tween(950, easing = LinearEasing)) }
            launch { scale.animateTo(1f, tween(1450, easing = LinearEasing)) }
            delay(1310)
            exiting.animateTo(0f, tween(260))
            onFinished()
        }
    }
    val description = stringResource(R.string.launch_artwork_description, stringResource(artwork.nameRes))
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exiting.value }
            .background(Color(0xFF090D1C))
            .clickable(role = Role.Button) { onFinished() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = appearing.value
                    scaleX = scale.value
                    scaleY = scale.value
                },
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val y = scan.value * height
            if (!reduceMotion && y in 0f..height) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, artwork.accent.copy(alpha = 0.21f), Color.Transparent),
                        startY = y - 35.dp.toPx(),
                        endY = y + 26.dp.toPx(),
                    ),
                    topLeft = Offset(0f, (y - 35.dp.toPx()).coerceAtLeast(0f)),
                    size = Size(width, 61.dp.toPx()),
                )
                drawLine(
                    color = artwork.accent.copy(alpha = 0.70f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0x35090D1C),
                    .66f to Color.Transparent,
                    1f to Color(0xF0090D1C),
                ),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.launch_brand_name),
                color = Color.White.copy(alpha = appearing.value),
                fontSize = 45.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 7.sp,
            )
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .height(1.dp)
                    .background(artwork.accent.copy(alpha = appearing.value * .85f)),
            )
        }
    }
}

/** Decode exactly one bundled splash image; loading happens on Dispatchers.IO. */
private fun decodeLaunchArtwork(assets: AssetManager, path: String): ImageBitmap? {
    if (hologramCharacterArtworks.none { it.launchAssetPath == path }) return null
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / sample > 2000) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }.asImageBitmap()
    }.getOrNull()
}