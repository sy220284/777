package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import android.content.res.AssetManager
import android.graphics.ImageDecoder
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.ui.artwork.hologramCharacterArtworks
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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

/** Dedicated welcome-screen artwork comes from the same catalog as the launch experience. */
internal val welcomeMotionPersonas = hologramCharacterArtworks

/** A restrained gallery preview: slow breathing, quiet crossfade and one discreet name label. */
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
    val breathing = remember { Animatable(0.5f) }
    LaunchedEffect(motionEnabled) {
        if (motionEnabled) {
            while (isActive) {
                breathing.animateTo(1f, tween(5200))
                breathing.animateTo(0f, tween(5200))
            }
        } else {
            breathing.snapTo(0.5f)
        }
    }
    val description = stringResource(R.string.local_welcome_persona_switch, stringResource(selection.nameRes))
    Box(
        modifier = Modifier
            .width(252.dp)
            .height(328.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF12141B))
            .clickable(role = Role.Button) {
                index = nextWelcomePersonaIndex(index, welcomeMotionPersonas.size)
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = selection,
            animationSpec = tween(if (reducedMotion) 0 else 380),
            label = "welcome-persona-subtle-transition",
        ) { persona ->
            val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = persona.assetPath) {
                value = withContext(Dispatchers.IO) {
                    decodeWelcomeMotionArtwork(context.assets, persona.assetPath)
                }
            }
            if (bitmap == null) {
                Icon(
                    imageVector = FeatherIcons.User,
                    contentDescription = null,
                    tint = DsTheme.colors.labelSecondary,
                    modifier = Modifier.width(46.dp).height(46.dp),
                )
            } else {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        if (motionEnabled) {
                            val magnitude = breathing.value - 0.5f
                            translationY = magnitude * 1.8f
                            scaleX = 1.006f + magnitude * 0.004f
                            scaleY = 1.006f + magnitude * 0.004f
                        }
                    },
                )
            }
        }
        Text(
            text = stringResource(selection.nameRes),
            color = Color.White.copy(alpha = 0.84f),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 15.dp),
        )
    }
}

private fun decodeWelcomeMotionArtwork(assets: AssetManager, path: String): ImageBitmap? {
    if (welcomeMotionPersonas.none { it.assetPath == path }) return null
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / sample > 1000) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }.asImageBitmap()
    }.getOrNull()
}