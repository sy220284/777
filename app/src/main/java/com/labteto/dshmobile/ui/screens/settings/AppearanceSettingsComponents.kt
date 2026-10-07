package com.labteto.dshmobile.ui.screens.settings

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.theme.AccentPalettes
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppearanceReadingPreview() {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.wallpaperSurface(WallpaperSurfaceLevel.CARD), DsShapes.block)
            .border(1.dp, colors.borderL2, DsShapes.block)
            .padding(DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(
            stringResource(R.string.settings_appearance_preview_assistant),
            style = DsType.chatBody.withReadingWeight(),
            color = colors.labelPrimary,
        )
        UserBubble(stringResource(R.string.settings_appearance_preview_user))
        Text(
            stringResource(R.string.settings_appearance_preview_hint),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
        )
    }
}

@Composable
internal fun ReadingPreferencesRow(
    settings: AppSettings,
    onTextScaleChange: (Float) -> Unit,
    onTextWeightChange: (Int) -> Unit,
    onTransparencyChange: (Float) -> Unit,
) {
    val colors = DsTheme.colors
    Text(
        stringResource(R.string.settings_text_size),
        style = DsType.std14Strong.withReadingWeight(),
        color = colors.labelPrimary,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(stringResource(R.string.settings_text_smaller), style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
        Slider(
            value = settings.textScale,
            onValueChange = onTextScaleChange,
            valueRange = 0.9f..1.3f,
            steps = 7,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.settings_text_scale_value, (settings.textScale * 100).toInt()),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.labelPrimary,
        )
    }

    Text(
        stringResource(R.string.settings_text_weight),
        style = DsType.std14Strong.withReadingWeight(),
        color = colors.labelPrimary,
    )
    DsSegmented(
        segments = listOf(
            DsSegment("0", stringResource(R.string.settings_text_weight_standard)),
            DsSegment("1", stringResource(R.string.settings_text_weight_medium)),
            DsSegment("2", stringResource(R.string.settings_text_weight_bold)),
        ),
        selectedKey = settings.textWeightAdjustment.toString(),
        onSelect = { onTextWeightChange(it.toIntOrNull() ?: 0) },
        stretch = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        stringResource(R.string.settings_component_transparency),
        style = DsType.std14Strong.withReadingWeight(),
        color = colors.labelPrimary,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(stringResource(R.string.settings_surface_clearer), style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
        Slider(
            value = settings.wallpaperSurfaceTransparency,
            onValueChange = onTransparencyChange,
            valueRange = 0f..1f,
            steps = 9,
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.settings_surface_airier), style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
    }
    Text(
        stringResource(R.string.settings_component_transparency_hint),
        style = DsType.small13.withReadingWeight(),
        color = colors.labelSecondary,
    )
}

@Composable
internal fun AppearanceRow(settings: AppSettings, onSelect: (String) -> Unit) {
    val colors = DsTheme.colors
    val options = listOf(
        "light" to stringResource(R.string.settings_appearance_light),
        "dark" to stringResource(R.string.settings_appearance_dark),
        "matte_black" to stringResource(R.string.settings_appearance_matte_black),
        "system" to stringResource(R.string.settings_appearance_system),
    )
    Column(modifier = Modifier.padding(vertical = DsSpacing.small)) {
        Text(
            stringResource(R.string.settings_appearance),
            style = DsType.std14.withReadingWeight(),
            color = colors.labelSecondary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        // 预览先行：每块内画出主题本身的底色与卡片条，选中块描 accent 边
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            options.take(2).forEach { (key, label) ->
                ThemePreviewBlock(
                    themeKey = key,
                    label = label,
                    selected = settings.themePreference == key,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(key) },
                )
            }
        }
        Spacer(Modifier.height(DsSpacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            options.takeLast(2).forEach { (key, label) ->
                ThemePreviewBlock(
                    themeKey = key,
                    label = label,
                    selected = settings.themePreference == key,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(key) },
                )
            }
        }
    }
}

@Composable
internal fun AccentThemeRow(settings: AppSettings, onSelect: (String) -> Unit) {
    val colors = DsTheme.colors
    Column(modifier = Modifier.padding(vertical = DsSpacing.small)) {
        Text(
            stringResource(R.string.settings_accent_theme),
            style = DsType.std14.withReadingWeight(),
            color = colors.labelSecondary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        Row(
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            AccentPalettes.ALL.forEach { palette ->
                val selected = settings.accentTheme == palette.key
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(68.dp)
                        .clip(DsShapes.cube)
                        .background(
                            if (selected) colors.accentTertiary else Color.Transparent,
                            DsShapes.cube,
                        )
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) colors.accent else colors.borderL2,
                            shape = DsShapes.cube,
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(palette.key) },
                        )
                        .padding(8.dp),
                ) {
                    // 色卡本体：亮档/暗档/浅衬 三段色条
                    Row(Modifier.fillMaxWidth().height(24.dp).clip(DsShapes.row)) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(palette.lightAccent))
                        Box(Modifier.weight(1f).fillMaxHeight().background(palette.darkAccent))
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(palette.lightAccent.copy(alpha = 0.35f)),
                        )
                    }
                    Spacer(Modifier.height(DsSpacing.xsmall))
                    Text(
                        palette.cnName,
                        style = DsType.caption11.withReadingWeight(),
                        color = if (selected) colors.accent else colors.labelTertiary,
                    )
                    if (selected) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.cube)
            .background(if (selected) colors.accentTertiary else colors.bgModulePlatform)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = DsSpacing.small),
    ) {
        Text(
            label,
            style = DsType.small13.withReadingWeight(),
            color = if (selected) colors.accent else colors.labelSecondary,
        )
    }
}

/**
 * Pick or drop the app-wide background image.
 *
 * The picked bytes are copied into app storage rather than the URI kept, which is why the document
 * picker is enough here: it is also the picker this app already uses for attachments, and one
 * picker is one thing to keep working.
 */
@Composable
internal fun BackgroundRow(
    path: String?,
    adaptiveContrast: Boolean,
    onAdaptiveContrastChange: (Boolean) -> Unit,
    onPick: (Uri) -> Unit,
    onClear: () -> Unit,
) {
    val colors = DsTheme.colors
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    Column(modifier = Modifier.padding(vertical = DsSpacing.small)) {
        Text(
            stringResource(R.string.settings_background_image),
            style = DsType.std14.withReadingWeight(),
            color = colors.labelSecondary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        if (path != null) {
            val imagePath = path
            val thumbnail by produceState<ImageBitmap?>(initialValue = null, key1 = imagePath) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(imagePath, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                        var sample = 1
                        while (bounds.outHeight / (sample * 2) >= 96) sample *= 2
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeFile(imagePath, opts)?.asImageBitmap()
                    }.getOrNull()
                }
            }
            thumbnail?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 96.dp, height = 56.dp)
                        .clip(DsShapes.cube),
                )
                Spacer(Modifier.height(DsSpacing.small))
            }
        }
        Text(
            stringResource(
                if (path == null) {
                    R.string.settings_background_image_none
                } else {
                    R.string.settings_background_image_active
                },
            ),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelTertiary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            AppearanceChip(stringResource(R.string.settings_background_image_choose), false) {
                picker.launch(arrayOf("image/*"))
            }
            if (path != null) {
                AppearanceChip(stringResource(R.string.settings_background_image_clear), false) {
                    onClear()
                }
            }
        }
        if (path != null) {
            Spacer(Modifier.height(DsSpacing.small))
            ToggleRow(
                stringResource(R.string.settings_background_adaptive_contrast),
                adaptiveContrast,
                stringResource(R.string.settings_background_adaptive_contrast_hint),
            ) { onAdaptiveContrastChange(!adaptiveContrast) }
        }
    }
}
