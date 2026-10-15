package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

/** Historic function name retained for callers; V6 now uses one full-width compact row per persona. */
@Composable
internal fun FluidPersonaGalleryGrid(
    entries: List<PersonaGalleryEntry>,
    onSelect: (String) -> Unit,
    onLongClick: (String) -> Unit,
    pinnedIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    animateOnEntrance: Boolean = true,
) {
    val animateEntrance = animateOnEntrance && !LocalSyReducedMotion.current
    val enterOffset = with(LocalDensity.current) { 12.dp.toPx() }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
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
            Box(Modifier.fillMaxWidth().graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * enterOffset
            }) {
                CompactPersonaRow(
                    entry = entry,
                    pinned = entry.id in pinnedIds,
                    managing = false,
                    selected = false,
                    dragEnabled = false,
                    onClick = { onSelect(entry.id) },
                    onLongClick = { onLongClick(entry.id) },
                    onTogglePin = {},
                    onMove = { false },
                )
            }
        }
    }
}
