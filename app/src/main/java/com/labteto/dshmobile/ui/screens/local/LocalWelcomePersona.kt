package com.labteto.dshmobile.ui.screens.local

import android.content.res.AssetManager
import android.graphics.ImageDecoder
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaPresetCatalog
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WelcomePersonaArtwork(
    val id: String,
    val name: String,
    val portraitPath: String = "",
    val presetAssetPath: String = "",
)

/** Saved gallery portraits take precedence; bundled gallery artwork covers fresh installs. */
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

/** A tap cycles the image only; it does not activate a character or change the chat session. */
@Composable
internal fun WelcomePersonaCarousel(gallery: List<PersonaGalleryEntry>) {
    val artworks = remember(gallery) {
        welcomePersonaArtworks(gallery, PersonaPresetCatalog.presets)
    }
    if (artworks.isEmpty()) {
        Icon(
            imageVector = FeatherIcons.User,
            contentDescription = null,
            tint = DsTheme.colors.labelSecondary,
            modifier = Modifier.size(88.dp),
        )
        return
    }
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    val selected = artworks[selectedIndex % artworks.size]
    val description = stringResource(R.string.local_welcome_persona_switch, selected.name)
    val shape = RoundedCornerShape(24.dp)
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(shape)
            .background(colors.characterAccentTertiary)
            .clickable(role = Role.Button, onClick = {
                selectedIndex = nextWelcomePersonaIndex(selectedIndex, artworks.size)
            })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = selected, animationSpec = tween(200), label = "home-persona-cycle") { artwork ->
            val context = LocalContext.current
            val bitmap by produceState<ImageBitmap?>(
                initialValue = null,
                key1 = artwork.portraitPath,
                key2 = artwork.presetAssetPath,
            ) {
                value = withContext(Dispatchers.IO) {
                    if (artwork.portraitPath.isNotBlank()) {
                        decodePersonaPortraitBitmap(artwork.portraitPath, maxEdgePx = 256)
                    } else {
                        decodeWelcomePresetArtwork(context.assets, artwork.presetAssetPath)
                    }
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.User,
                    contentDescription = null,
                    tint = colors.characterAccent,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

private fun decodeWelcomePresetArtwork(assets: AssetManager, path: String): ImageBitmap? {
    if (!path.startsWith("persona-presets/") || ".." in path || !path.endsWith(".webp")) return null
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            val longest = maxOf(info.size.width, info.size.height)
            while (longest / sample > 256) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }.asImageBitmap()
    }.getOrNull()
}
