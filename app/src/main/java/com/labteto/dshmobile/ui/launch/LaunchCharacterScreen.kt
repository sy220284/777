package com.labteto.dshmobile.ui.launch

import android.content.res.AssetManager
import android.graphics.ImageDecoder
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.artwork.HologramCharacterArtwork
import com.labteto.dshmobile.ui.artwork.hologramCharacterArtworks
import com.labteto.dshmobile.ui.artwork.characterArtworkSampleSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only regular cold launcher entry; notifications and restored sessions remain direct. */
internal fun shouldShowLaunchArtwork(
    restored: Boolean,
    action: String?,
    hasLauncherCategory: Boolean,
    hasSessionRequest: Boolean,
): Boolean = !restored && !hasSessionRequest &&
    action == "android.intent.action.MAIN" && hasLauncherCategory

internal fun nextLaunchArtworkIndex(index: Int, count: Int): Int =
    if (count <= 1) 0 else (index.coerceIn(0, count - 1) + 1) % count

/** Pure, quiet full-screen art: no labels, badges, scanning bars, flares or particles. */
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
        value = withContext(Dispatchers.IO) {
            decodeLaunchArtwork(context.assets, artwork.launchAssetPath)
        }
    }
    val opacity = remember { Animatable(0f) }
    val scale = remember { Animatable(1.012f) }
    LaunchedEffect(artwork.id, reduceMotion) {
        if (reduceMotion) {
            opacity.snapTo(1f)
            scale.snapTo(1f)
            delay(180)
            onFinished()
        } else {
            launch { scale.animateTo(1f, tween(1020, easing = FastOutSlowInEasing)) }
            opacity.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
            delay(610)
            opacity.animateTo(0f, tween(230, easing = FastOutSlowInEasing))
            onFinished()
        }
    }
    val description = stringResource(R.string.launch_artwork_description, stringResource(artwork.nameRes))
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF12141B))
            .clickable(role = Role.Button, onClick = onFinished)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = opacity.value
                    scaleX = scale.value
                    scaleY = scale.value
                },
            )
        }
    }
}

private fun decodeLaunchArtwork(assets: AssetManager, path: String): ImageBitmap? {
    if (hologramCharacterArtworks.none { it.launchAssetPath == path }) return null
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // 1440×3200 launch assets must remain at native resolution on high-density screens.
            val sample = characterArtworkSampleSize(maxOf(info.size.width, info.size.height), 3600)
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }.asImageBitmap()
    }.getOrNull()
}