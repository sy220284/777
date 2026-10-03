package com.labteto.dshmobile.ui.screens.local

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun PersonaGalleryDetailHeaderV3(
    entry: PersonaGalleryEntry,
    relationSummary: String,
    busy: Boolean,
    onChoosePortrait: () -> Unit,
    onRemovePortrait: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            LocalPersonaHeaderAvatar(entry.persona.name, entry.portraitPath)
            Column(Modifier.weight(1f)) {
                Text(
                    entry.persona.name,
                    style = DsType.base16Strong.withReadingWeight(),
                    color = DsTheme.colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.persona.identity.ifBlank { relationSummary },
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.persona.identity.isNotBlank()) {
                    Text(
                        relationSummary,
                        style = DsType.caption11.withReadingWeight(),
                        color = DsTheme.colors.labelTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            DsButton(
                text = stringResource(
                    if (entry.portraitPath.isBlank()) R.string.persona_gallery_portrait_add
                    else R.string.persona_gallery_portrait_replace,
                ),
                onClick = onChoosePortrait,
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Ghost,
                enabled = !busy,
            )
            if (entry.portraitPath.isNotBlank()) {
                DsButton(
                    text = stringResource(R.string.persona_gallery_portrait_remove),
                    onClick = onRemovePortrait,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun PersonaGalleryAddPanel(
    presets: List<PersonaPreset>,
    busy: Boolean,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onInstallPreset: (String) -> Unit,
    onRequestHidePreset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = FeatherIcons.ArrowLeft,
                contentDescription = stringResource(R.string.persona_gallery_back_to_list),
                onClick = onBack,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.persona_gallery_add_title),
                    style = DsType.base16Strong.withReadingWeight(),
                    color = DsTheme.colors.labelPrimary,
                )
                Text(
                    stringResource(R.string.persona_gallery_add_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = DsTheme.colors.labelTertiary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsButton(
                text = stringResource(R.string.local_persona_picker_new),
                onClick = onCreate,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_import_file),
                onClick = onImport,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                variant = DsButtonVariant.Outline,
            )
        }
        if (presets.isNotEmpty()) {
            PersonaGallerySectionLabel(stringResource(R.string.persona_gallery_presets_title))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                items(presets, key = PersonaPreset::id) { preset ->
                    Surface(
                        shape = DsShapes.row,
                        color = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                        ) {
                            LocalPersonaHeaderAvatar(preset.persona.name, "")
                            Column(Modifier.weight(1f)) {
                                Text(
                                    preset.persona.name,
                                    style = DsType.std14Strong.withReadingWeight(),
                                    color = DsTheme.colors.labelPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    preset.franchise + " · " + preset.summary,
                                    style = DsType.caption11.withReadingWeight(),
                                    color = DsTheme.colors.labelTertiary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DsButton(
                                text = stringResource(R.string.persona_gallery_preset_add),
                                onClick = { onInstallPreset(preset.id) },
                                enabled = !busy,
                                variant = DsButtonVariant.Ghost,
                                size = DsButtonSize.Small,
                            )
                            DsIconButton(
                                icon = FeatherIcons.X,
                                contentDescription = stringResource(R.string.persona_gallery_preset_hide),
                                onClick = { onRequestHidePreset(preset.id) },
                                enabled = !busy,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaGallerySectionLabel(title: String) {
    Text(
        title,
        style = DsType.caption11.withReadingWeight(),
        color = DsTheme.colors.labelTertiary,
        modifier = Modifier.padding(start = DsSpacing.small, top = DsSpacing.xsmall),
    )
}

@Composable
private fun PersonaGalleryEmptyState(
    title: String,
    body: String,
    primary: String,
    secondary: String?,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(
                FeatherIcons.User,
                contentDescription = null,
                tint = DsTheme.colors.labelTertiary,
                modifier = Modifier.size(28.dp),
            )
            Text(
                title,
                style = DsType.base16Strong.withReadingWeight(),
                color = DsTheme.colors.labelPrimary,
            )
            Text(
                body,
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.labelTertiary,
            )
            DsButton(
                text = primary,
                onClick = onPrimary,
                size = DsButtonSize.Small,
            )
            secondary?.let {
                DsButton(
                    text = it,
                    onClick = onSecondary,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CompactPersonaRow(
    entry: PersonaGalleryEntry,
    pinned: Boolean,
    managing: Boolean,
    selected: Boolean,
    dragEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var dragDistance by remember(entry.id) { mutableFloatStateOf(0f) }
    Surface(
        shape = DsShapes.row,
        color = DsTheme.colors.wallpaperSurface(
            WallpaperSurfaceLevel.CARD,
            base = if (selected) DsTheme.colors.sidebarNavActive else DsTheme.colors.bgBase,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (managing) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                )
            }
            LocalPersonaHeaderAvatar(entry.persona.name, entry.portraitPath)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.persona.name,
                        style = DsType.std14Strong.withReadingWeight(),
                        color = DsTheme.colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (pinned && !managing) {
                        Icon(
                            FeatherIcons.Pin,
                            contentDescription = null,
                            tint = DsTheme.colors.labelCaption,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    entry.persona.identity.ifBlank {
                        stringResource(
                            R.string.persona_gallery_compact_meta,
                            entry.stories.size,
                            entry.totalDialogueCount(),
                        )
                    },
                    style = DsType.caption11.withReadingWeight(),
                    color = DsTheme.colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (managing) {
                DsIconButton(
                    icon = FeatherIcons.Pin,
                    contentDescription = stringResource(
                        if (pinned) R.string.persona_gallery_unpin else R.string.persona_gallery_pin,
                    ),
                    onClick = onTogglePin,
                    tint = if (pinned) DsTheme.colors.accent else DsTheme.colors.labelTertiary,
                )
                Box(
                    modifier = Modifier
                        .size(DsSpacing.touchTarget)
                        .pointerInput(entry.id, dragEnabled) {
                            if (!dragEnabled) return@pointerInput
                            detectDragGestures(
                                onDragEnd = { dragDistance = 0f },
                                onDragCancel = { dragDistance = 0f },
                            ) { change, dragAmount ->
                                change.consume()
                                dragDistance += dragAmount.y
                                val threshold = 44.dp.toPx()
                                while (abs(dragDistance) >= threshold) {
                                    val direction = if (dragDistance > 0f) 1 else -1
                                    onMove(direction)
                                    dragDistance -= direction * threshold
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        FeatherIcons.Menu,
                        contentDescription = stringResource(R.string.persona_gallery_reorder),
                        tint = if (dragEnabled) DsTheme.colors.labelSecondary else DsTheme.colors.labelCaption,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
