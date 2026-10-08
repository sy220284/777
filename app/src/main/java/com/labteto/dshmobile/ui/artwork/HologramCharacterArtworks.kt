package com.labteto.dshmobile.ui.artwork

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.R

/** Five character visual identities shared by quiet launch and welcome surfaces. */
internal data class HologramCharacterArtwork(
    val id: String,
    @StringRes val nameRes: Int,
    val assetPath: String,
    val launchAssetPath: String,
    val accent: Color,
    val highlight: Color,
)

internal val hologramCharacterArtworks = listOf(
    HologramCharacterArtwork("ayaka", R.string.hologram_ayaka, "persona-motion/ayaka.webp", "persona-launch/ayaka.webp", Color(0xFF9CBBD0), Color(0xFFAAB1C9)),
    HologramCharacterArtwork("march7", R.string.hologram_march7, "persona-motion/march7.webp", "persona-launch/march7.webp", Color(0xFFC9A1BC), Color(0xFF9CBCCC)),
    HologramCharacterArtwork("shenxinghui", R.string.hologram_shenxinghui, "persona-motion/shenxinghui.webp", "persona-launch/shenxinghui.webp", Color(0xFFC8B99B), Color(0xFFA0B4CA)),
    HologramCharacterArtwork("xiayizhou", R.string.hologram_xiayizhou, "persona-motion/xiayizhou.webp", "persona-launch/xiayizhou.webp", Color(0xFFBD8E84), Color(0xFFC1A38A)),
    HologramCharacterArtwork("klee", R.string.hologram_klee, "persona-motion/klee.webp", "persona-launch/klee.webp", Color(0xFFCAA17C), Color(0xFFD0BD9C)),
)