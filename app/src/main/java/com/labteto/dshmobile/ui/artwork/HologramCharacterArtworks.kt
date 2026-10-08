package com.labteto.dshmobile.ui.artwork

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.R

/** One source of truth for the five original hologram-themed visuals. */
internal data class HologramCharacterArtwork(
    val id: String,
    @StringRes val nameRes: Int,
    val assetPath: String,
    val launchAssetPath: String,
    val accent: Color,
    val highlight: Color,
)

internal val hologramCharacterArtworks = listOf(
    HologramCharacterArtwork("ayaka", R.string.hologram_ayaka, "persona-motion/ayaka.webp", "persona-launch/ayaka.webp", Color(0xFF86D7FF), Color(0xFFB5A6FF)),
    HologramCharacterArtwork("march7", R.string.hologram_march7, "persona-motion/march7.webp", "persona-launch/march7.webp", Color(0xFFFFA8E7), Color(0xFF8ACBFF)),
    HologramCharacterArtwork("shenxinghui", R.string.hologram_shenxinghui, "persona-motion/shenxinghui.webp", "persona-launch/shenxinghui.webp", Color(0xFFF6DFAD), Color(0xFF94BEFF)),
    HologramCharacterArtwork("xiayizhou", R.string.hologram_xiayizhou, "persona-motion/xiayizhou.webp", "persona-launch/xiayizhou.webp", Color(0xFFFF957B), Color(0xFFFFC184)),
    HologramCharacterArtwork("klee", R.string.hologram_klee, "persona-motion/klee.webp", "persona-launch/klee.webp", Color(0xFFFFA06C), Color(0xFFFFE1A0)),
)