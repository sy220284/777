package com.labteto.dshmobile.ui.screens.local

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Design 06: artwork cards on a compact two-column grid; management keeps the real reorder flow. */
@Composable
internal fun FluidPersonaGalleryGrid(entries: List<PersonaGalleryEntry>, onSelect: (String) -> Unit,
    onLongClick: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(GridCells.Fixed(2), modifier,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        items(entries, key = PersonaGalleryEntry::id) { entry ->
            FluidPersonaGalleryCard(entry, { onSelect(entry.id) }, { onLongClick(entry.id) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FluidPersonaGalleryCard(entry: PersonaGalleryEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = DsTheme.colors
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, entry.portraitPath) {
        value = withContext(Dispatchers.IO) { decodePersonaPortraitBitmap(entry.portraitPath, maxEdgePx = 480) }
    }
    Surface(shape = DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL1),
        modifier = Modifier.fillMaxWidth().testTag("personaGalleryEntry_${entry.id}")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column {
            Surface(color = colors.characterAccentTertiary, modifier = Modifier.fillMaxWidth().height(140.dp)) {
                val portrait = image
                if (portrait != null) Image(portrait, entry.persona.name, contentScale = ContentScale.Fit)
                else Box(contentAlignment = Alignment.Center) {
                    Text(entry.persona.name.let { name ->
                        if (name.isEmpty()) "" else String(Character.toChars(name.codePointAt(0)))
                    }, style = DsType.largeTitle28, color = colors.characterAccent)
                }
            }
            Column(Modifier.padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(entry.persona.name, style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
                val saved = entry.story()?.chatState
                Text(saved?.takeIf { it.updatedAt > 0 }?.relationshipState
                    ?: stringResource(R.string.fluid_item_count, entry.stories.size),
                    style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
            }
        }
    }
}
