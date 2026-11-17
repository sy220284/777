package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover-based navigation. Editing, selection and reordering stay in the existing management list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StudioPersonaTile(
    entry: PersonaGalleryEntry,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalSyColors.current
    val portrait by produceState<ImageBitmap?>(null, entry.portraitPath) {
        value = withContext(Dispatchers.IO) {
            decodePersonaPortraitBitmap(entry.portraitPath, maxEdgePx = 640)
        }
    }
    val compact = LocalConfiguration.current.screenHeightDp < 700 || LocalDensity.current.fontScale >= 1.3f
    val target = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .testTag("personaGalleryEntry_${entry.id}")
        .combinedClickable(role = Role.Button, onClick = onClick,
            onLongClickLabel = stringResource(R.string.persona_gallery_manage), onLongClick = onLongClick)

    @Composable fun Cover(modifier: Modifier) {
        Box(modifier.aspectRatio(.78f).clip(RoundedCornerShape(20.dp))
            .background(colors.materialL2), contentAlignment = Alignment.Center) {
            val image = portrait
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            } else {
                // A text cover is intentional when no image is available; do not borrow another persona.
                Text(entry.persona.name.take(1), color = colors.textSecondary,
                    style = DsType.largeTitle28.withReadingWeight())
            }
        }
    }
    @Composable fun Details(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.persona.name, modifier = Modifier.weight(1f),
                    style = DsType.base16Strong.withReadingWeight(), color = colors.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (pinned) Icon(FeatherIcons.Pin, contentDescription = stringResource(R.string.persona_gallery_pinned),
                    tint = colors.linkInk, modifier = Modifier.size(16.dp))
            }
            Text(entry.persona.coreIdentity.ifBlank {
                stringResource(R.string.persona_gallery_compact_meta, entry.stories.size, entry.totalDialogueCount())
            }, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                style = DsType.small13.withReadingWeight(), color = colors.textSecondary,
                maxLines = if (compact) 3 else 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (compact) {
        Row(target.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Cover(Modifier.width(92.dp))
            Details(Modifier.weight(1f))
        }
    } else {
        Column(target, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Cover(Modifier.fillMaxWidth())
            Details(Modifier.fillMaxWidth())
        }
    }
}
