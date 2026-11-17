package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.ui.shenyan.LocalSyReducedMotion
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.shenyan.designsystem.foundation.SyMetric
import kotlinx.coroutines.delay

/** V7 image covers. Large font or narrow phones use one column; management remains a list. */
@Composable
internal fun FluidPersonaGalleryGrid(
    entries: List<PersonaGalleryEntry>,
    onSelect: (String) -> Unit,
    onLongClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    animateOnEntrance: Boolean = true,
    pinnedIds: Set<String> = emptySet(),
) {
    val reduced = LocalSyReducedMotion.current
    val animateEntrance = animateOnEntrance && !reduced
    val tempo = com.shenyan.designsystem.foundation.LocalSyMotionTempo.current
    val motion = com.shenyan.designsystem.foundation.SyMotion
    val enterOffset = with(LocalDensity.current) { 12.dp.toPx() }
    val columns = if (LocalDensity.current.fontScale >= 1.3f ||
        LocalConfiguration.current.screenWidthDp < 360 ||
        LocalConfiguration.current.screenHeightDp < 700) 1 else 2
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSection),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            var appeared by rememberSaveable(entry.id, animateEntrance) {
                mutableStateOf(!animateEntrance || index >= DsAnimations.cascadeMaxItems)
            }
            LaunchedEffect(entry.id, animateEntrance) {
                if (!appeared) {
                    delay(index.coerceAtMost(DsAnimations.cascadeMaxItems - 1) *
                        motion.duration(motion.cascadeStaggerMs, reduced, tempo).toLong())
                    appeared = true
                }
            }
            val reveal by animateFloatAsState(
                targetValue = if (appeared) 1f else 0f,
                animationSpec = tween(motion.duration(motion.pushMs, reduced, tempo), easing = motion.ease),
                label = "personaGalleryCascade",
            )
            Box(Modifier.fillMaxWidth().graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * enterOffset
            }) {
                StudioPersonaTile(
                    entry = entry,
                    pinned = entry.id in pinnedIds,
                    onClick = { onSelect(entry.id) },
                    onLongClick = { onLongClick(entry.id) },
                )
            }
        }
    }
}
