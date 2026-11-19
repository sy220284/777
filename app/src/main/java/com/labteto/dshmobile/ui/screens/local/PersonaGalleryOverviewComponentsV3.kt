package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric




import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import com.shenyan.designsystem.components.SyFlatSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.shenyan.designsystem.components.SyCheckboxControl
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.shenyan.designsystem.components.SyIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import com.labteto.dshmobile.ui.theme.SyShapes
import com.labteto.dshmobile.ui.theme.SyType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal data class PersonaPhotoFrameLayout(
    val imageAspectRatio: Float,
    val widthFraction: Float,
)

internal fun personaPhotoFrameLayout(
    imageWidth: Int,
    imageHeight: Int,
): PersonaPhotoFrameLayout {
    val imageAspectRatio = if (imageWidth > 0 && imageHeight > 0) {
        (imageWidth.toFloat() / imageHeight.toFloat()).coerceIn(2f / 3f, 1.5f)
    } else {
        1f
    }
    val widthFraction = when {
        imageAspectRatio < 0.85f -> 0.64f
        imageAspectRatio > 1.15f -> 0.90f
        else -> 0.78f
    }
    return PersonaPhotoFrameLayout(
        imageAspectRatio = imageAspectRatio,
        widthFraction = widthFraction,
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun PersonaGalleryDetailHeaderV3(
    entry: PersonaGalleryEntry,
    relationSummary: String,
    busy: Boolean,
    onChoosePortrait: () -> Unit,
    onManagePortrait: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val portrait by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = entry.portraitPath,
    ) {
        value = withContext(Dispatchers.IO) {
            decodePersonaPortraitBitmap(entry.portraitPath, maxEdgePx = 1_600)
        }
    }
    val frameLayout = remember(portrait) {
        personaPhotoFrameLayout(
            imageWidth = portrait?.width ?: 0,
            imageHeight = portrait?.height ?: 0,
        )
    }
    val subtitle = entry.persona.coreIdentity.ifBlank { relationSummary }


    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(frameLayout.widthFraction)
                .combinedClickable(
                    enabled = !busy,
                    onClick = {
                        if (entry.portraitPath.isBlank()) onChoosePortrait() else onManagePortrait()
                    },
                    onLongClick = {
                        if (entry.portraitPath.isNotBlank()) onManagePortrait()
                    },
                ),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 8.dp,
                    top = 8.dp,
                    end = 8.dp,
                    bottom = 10.dp,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SyFlatSurface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.lilac.copy(alpha = .18f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(frameLayout.imageAspectRatio),
                ) {
                    val resolvedPortrait = portrait
                    if (resolvedPortrait != null) {
                        Image(
                            bitmap = resolvedPortrait,
                            contentDescription = entry.persona.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.Center,
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                            ) {
                                Icon(
                                    FeatherIcons.User,
                                    contentDescription = null,
                                    tint = colors.lilac,
                                    modifier = Modifier.size(32.dp),
                                )
                                Text(
                                    stringResource(R.string.persona_gallery_portrait_photo_empty),
                                    style = SyType.small13.withReadingWeight(),
                                    color = colors.textSecondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(SyMetric.gapSm))
        Text(
            entry.persona.name,
            style = SyType.title22.withReadingWeight().copy(fontFamily = SyType.contentFont),
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (entry.persona.coreIdentity.isNotBlank()) {
            Text(
                subtitle,
                style = SyType.small13.withReadingWeight().copy(fontFamily = SyType.contentFont),
                color = colors.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            relationSummary,
            style = SyType.small13.withReadingWeight().copy(fontFamily = SyType.contentFont),
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}



@Composable
internal fun PersonaGalleryAddPanel(
    presets: List<PersonaPreset>,
    installedPresetIds: Set<String>,
    busy: Boolean,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onInstallPreset: (String) -> Unit,
    onRequestHidePreset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = SyMetric.gapLg),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = SyMetric.touch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SyIconButton(
                icon = FeatherIcons.ArrowLeft,
                contentDescription = stringResource(R.string.persona_gallery_back_to_list),
                onClick = onBack,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.persona_gallery_add_title),
                    style = SyType.base16Strong.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                )
                Text(
                    stringResource(R.string.persona_gallery_add_hint),
                    style = SyType.caption11.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val create = stringResource(R.string.local_persona_picker_new)
            val importLabel = stringResource(R.string.persona_gallery_import_file)
            val measurer = rememberTextMeasurer()
            val style = SyType.std14Strong.withReadingWeight()
            val density = LocalDensity.current
            val widest = with(density) {
                maxOf(measurer.measure(create, style, softWrap = false).size.width,
                    measurer.measure(importLabel, style, softWrap = false).size.width).toDp()
            }
            if (maxWidth < (widest + SyMetric.gapLg * 2) * 2 + SyMetric.gapMd) {
                Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                    SyButton(create, onCreate, Modifier.fillMaxWidth(), enabled = !busy)
                    SyButton(importLabel, onImport, Modifier.fillMaxWidth(), enabled = !busy,
                        variant = SyButtonVariant.Outline)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                    SyButton(create, onCreate, Modifier.weight(1f), enabled = !busy)
                    SyButton(importLabel, onImport, Modifier.weight(1f), enabled = !busy,
                        variant = SyButtonVariant.Outline)
                }
            }
        }
        if (presets.isNotEmpty()) {
            PersonaGallerySectionLabel(stringResource(R.string.persona_gallery_presets_title))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                items(presets, key = PersonaPreset::id) { preset ->
                    PersonaPresetRow(
                        preset = preset,
                        installed = preset.id in installedPresetIds,
                        busy = busy,
                        onInstall = { onInstallPreset(preset.id) },
                        onHide = { onRequestHidePreset(preset.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonaPresetRow(
    preset: PersonaPreset,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val nameStyle = SyType.std14Strong.withReadingWeight().copy(fontFamily = SyType.contentFont)
    val addLabel = stringResource(if (installed) R.string.persona_gallery_preset_taken
        else R.string.persona_gallery_preset_add)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val nameWidth = with(density) { measurer.measure(preset.persona.name, nameStyle,
        softWrap = false).size.width.toDp() }
    val actionWidth = with(density) { measurer.measure(addLabel, nameStyle,
        softWrap = false).size.width.toDp() }
    var menuOpen by remember { mutableStateOf(false) }
    val triggerFocus = remember { FocusRequester() }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(
        horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm,
    )) {
        val inline = maxWidth >= nameWidth + actionWidth + SyMetric.touch * 2 + SyMetric.gapLg * 3
        val avatar: @Composable () -> Unit = {
            LocalPersonaHeaderAvatar(name = preset.persona.name, portraitPath = "",
                presetAssetPath = preset.artwork?.assetPath)
        }
        val name: @Composable () -> Unit = {
            Text(preset.persona.name, Modifier.testTag("persona-preset-name:" + preset.id),
                color = colors.text, style = nameStyle)
        }
        val summary: @Composable () -> Unit = {
            Text(preset.franchise + " · " + preset.summary,
                color = colors.textTertiary, style = SyType.caption11.withReadingWeight()
                    .copy(fontFamily = SyType.contentFont),
                maxLines = if (inline) 2 else 3, overflow = TextOverflow.Ellipsis)
        }
        val actions: @Composable () -> Unit = {
            if (installed) Text(addLabel, style = SyType.caption11.withReadingWeight(), color = colors.textSecondary)
            else SyButton(addLabel, onInstall, enabled = !busy, variant = SyButtonVariant.Ghost)
            Box {
                SyIconButton(FeatherIcons.MoreVertical,
                    contentDescription = stringResource(R.string.persona_gallery_preset_more, preset.persona.name),
                    onClick = { menuOpen = true }, enabled = !busy,
                    modifier = Modifier.focusRequester(triggerFocus))
                SyPopupMenu(expanded = menuOpen,
                    onDismiss = {
                        menuOpen = false
                        triggerFocus.requestFocus()
                    },
                    items = listOf(SyMenuItem(
                        text = stringResource(R.string.persona_gallery_preset_hide),
                        onClick = onHide,
                    )))
            }
        }
        if (inline) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                avatar()
                Column(Modifier.weight(1f)) {
                    name()
                    summary()
                }
                actions()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                    avatar()
                    Box(Modifier.weight(1f)) { name() }
                }
                summary()
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) { actions() }
            }
        }
    }
}

@Composable
internal fun PersonaGallerySectionLabel(title: String) {
    Text(
        title,
        style = SyType.caption11.withReadingWeight(),
        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
        modifier = Modifier.padding(start = SyMetric.gapMd, top = SyMetric.gapSm),
    )
}

@Composable
internal fun PersonaGalleryEmptyState(
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapSection),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Icon(
                FeatherIcons.User,
                contentDescription = null,
                tint = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                modifier = Modifier.size(28.dp),
            )
            Text(
                title,
                style = SyType.base16Strong.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
            )
            Text(
                body,
                style = SyType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
            )
            SyButton(
                text = primary,
                onClick = onPrimary,
                size = SyButtonSize.Small,
            )
            secondary?.let {
                SyButton(
                    text = it,
                    onClick = onSecondary,
                    size = SyButtonSize.Small,
                    variant = SyButtonVariant.Ghost,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun CompactPersonaRow(
    entry: PersonaGalleryEntry,
    pinned: Boolean,
    managing: Boolean,
    selected: Boolean,
    dragEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    onMove: (Int) -> Boolean,
) {
    var dragDistance by remember(entry.id) { mutableFloatStateOf(0f) }
    val tick = rememberHapticPulse(minIntervalMillis = 65L)
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val rowColor = if (selected) colors.selected.copy(alpha = .24f) else Color.Transparent
    SyFlatSurface(
        shape = SyShapes.row,
        color = rowColor,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("personaGalleryEntry_${entry.id}")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = SyMetric.gapMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            if (managing) {
                SyCheckboxControl(
                    checked = selected,
                    onCheckedChange = { onClick() },
                )
            }
            LocalPersonaHeaderAvatar(entry.persona.name, entry.portraitPath)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.persona.name,
                        style = SyType.std14Strong.withReadingWeight().copy(fontFamily = SyType.contentFont),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (pinned && !managing) {
                        Icon(
                            FeatherIcons.Pin,
                            contentDescription = null,
                            tint = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    entry.persona.coreIdentity.ifBlank {
                        stringResource(
                            R.string.persona_gallery_compact_meta,
                            entry.stories.size,
                            entry.totalDialogueCount(),
                        )
                    },
                    style = SyType.caption11.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!managing) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (managing) {
                SyIconButton(
                    icon = FeatherIcons.Pin,
                    contentDescription = stringResource(
                        if (pinned) R.string.persona_gallery_unpin else R.string.persona_gallery_pin,
                    ),
                    onClick = onTogglePin,
                    tint = if (pinned) com.shenyan.designsystem.foundation.LocalSyColors.current.primary else com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                )
                Box(
                    modifier = Modifier
                        .size(SyMetric.touch)
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
                                    if (onMove(direction)) tick()
                                    dragDistance -= direction * threshold
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        FeatherIcons.Menu,
                        contentDescription = stringResource(R.string.persona_gallery_reorder),
                        tint = if (dragEnabled) com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary else com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
