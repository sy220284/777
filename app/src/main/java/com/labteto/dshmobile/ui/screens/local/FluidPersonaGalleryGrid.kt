package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.ui.theme.*
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Design 06: artwork cards on a compact two-column grid; management keeps the real reorder flow. */
@Composable
internal fun FluidPersonaGalleryGrid(entries: List<PersonaGalleryEntry>, onSelect: (String) -> Unit,
    onLongClick: (String) -> Unit, modifier: Modifier = Modifier,
    animateOnEntrance: Boolean = true) {
    val reduced = com.labteto.dshmobile.ui.shenyan.LocalSyReducedMotion.current
    val animateEntrance = animateOnEntrance && !reduced
    LazyVerticalGrid(GridCells.Fixed(2), modifier,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            // Screen 06: reference 360ms cascade, 80ms stagger. The first ten
            // cards animate once; a long gallery must never queue hundreds of delays.
            // The V6 reveal is a first-entry flourish, not a mandatory animation
            // each time a detail screen returns to the same gallery list.
            var appeared by rememberSaveable(entry.id, animateEntrance) {
                mutableStateOf(!animateEntrance || index >= DsAnimations.cascadeMaxItems)
            }
            LaunchedEffect(entry.id, animateEntrance) {
                if (!appeared) {
                    delay(index.coerceAtMost(DsAnimations.cascadeMaxItems - 1) *
                        DsAnimations.cascadeStaggerMs)
                    appeared = true
                }
            }
            val reveal by animateFloatAsState(
                targetValue = if (appeared) 1f else 0f,
                animationSpec = DsAnimations.cascade,
                label = "personaGalleryCascade",
            )
            val enterOffset = with(LocalDensity.current) { 12.dp.toPx() }
            Box(Modifier.graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * enterOffset
            }) {
                FluidPersonaGalleryCard(entry, { onSelect(entry.id) }, { onLongClick(entry.id) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FluidPersonaGalleryCard(entry: PersonaGalleryEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val pulse = rememberHapticPulse()
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, entry.portraitPath) {
        value = withContext(Dispatchers.IO) { decodePersonaPortraitBitmap(entry.portraitPath, maxEdgePx = 480) }
    }
    SyMaterialSurface(shape = DsShapes.block,
        tier = SyMaterialTier.L3_CARD,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth().testTag("personaGalleryEntry_${entry.id}")
            .combinedClickable(onClick = onClick, onLongClick = { pulse(); onLongClick() })) {
        Column {
            Surface(color = colors.lilac.copy(alpha = .18f), modifier = Modifier.fillMaxWidth().height(140.dp)) {
                val portrait = image
                if (portrait != null) Image(portrait, entry.persona.name, contentScale = ContentScale.Fit)
                else Box(contentAlignment = Alignment.Center) {
                    Text(entry.persona.name.let { name ->
                        if (name.isEmpty()) "" else String(Character.toChars(name.codePointAt(0)))
                    }, style = DsType.largeTitle28, color = colors.lilac)
                }
            }
            Column(Modifier.padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(entry.persona.name, style = DsType.std14Strong.withReadingWeight(), color = colors.text)
                val saved = entry.story(null)?.chatState
                Text(saved?.takeIf { it.updatedAt > 0 }?.relationshipState
                    ?: stringResource(R.string.fluid_item_count, entry.stories.size),
                    style = DsType.caption11.withReadingWeight(), color = colors.textSecondary)
            }
        }
    }
}
