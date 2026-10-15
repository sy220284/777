package com.labteto.dshmobile.ui.screens.settings

import com.shenyan.designsystem.foundation.SyMetric

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
import com.shenyan.designsystem.foundation.LocalSyColors
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SySlider
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.shenyan.SyUserBubble
import com.labteto.dshmobile.ui.theme.AccentPalettes
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.wallpaperDecodeSample
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppearanceReadingPreview() {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = SyMetric.gapSm, vertical = SyMetric.gapXs),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Text(
            stringResource(R.string.settings_appearance_preview_assistant),
            style = DsType.small13.withReadingWeight(),
            color = colors.text,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            SyUserBubble(stringResource(R.string.settings_appearance_preview_user))
        }
    }
}

@Composable
internal fun ReadingPreferencesRow(
    settings: AppSettings,
    onTextScaleChange: (Float) -> Unit,
    onTextWeightChange: (Int) -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Text(
        stringResource(R.string.settings_text_size),
        style = DsType.std14Strong.withReadingWeight(),
        color = colors.text,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Text(stringResource(R.string.settings_text_smaller), style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
        SySlider(
            value = settings.textScale,
            onValueChange = onTextScaleChange,
            valueRange = com.labteto.dshmobile.connection.AppDisplayLimits.MIN_READING_SCALE..com.labteto.dshmobile.connection.AppDisplayLimits.MAX_READING_SCALE,
            steps = 5,
            hapticSegments = 6,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.settings_text_scale_value, (settings.textScale * 100).toInt()),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.text,
        )
    }

    Text(
        stringResource(R.string.settings_text_weight),
        style = DsType.std14Strong.withReadingWeight(),
        color = colors.text,
    )
    SySegmented(
        segments = listOf(
            SySegment("0", stringResource(R.string.settings_text_weight_standard)),
            SySegment("1", stringResource(R.string.settings_text_weight_medium)),
            SySegment("2", stringResource(R.string.settings_text_weight_bold)),
        ),
        selectedKey = settings.textWeightAdjustment.toString(),
        hapticOnChange = true,
        onSelect = { onTextWeightChange(it.toIntOrNull() ?: 0) },
        stretch = true,
        modifier = Modifier.fillMaxWidth(),
    )


}

@Composable
internal fun AppearanceAdvancedControls(
    settings: AppSettings,
    onAmbientChange: (Float) -> Unit,
    onTransparencyChange: (Float) -> Unit,
    onMotionChange: (Float) -> Unit,
    onEmphasisChange: (Float) -> Unit,
    onAdaptiveContrastChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val colors = LocalSyColors.current
    Text(stringResource(R.string.settings_advanced_color_background),
        style = DsType.std14Strong.withReadingWeight(), color = colors.text)
    AppearanceAdvancedSlider(
        stringResource(R.string.settings_advanced_ambient), settings.ambientGlow, 0f..1f, onAmbientChange,
        enabled = settings.backgroundImagePath == null,
    )
    ToggleRow(
        stringResource(R.string.settings_background_adaptive_contrast),
        settings.backgroundAdaptiveContrast,
    ) { onAdaptiveContrastChange(!settings.backgroundAdaptiveContrast) }

    Spacer(Modifier.height(SyMetric.gapSm))
    Text(stringResource(R.string.settings_advanced_material),
        style = DsType.std14Strong.withReadingWeight(), color = colors.text)
    AppearanceAdvancedSlider(
        stringResource(R.string.settings_component_transparency),
        settings.wallpaperSurfaceTransparency, 0f..1f, onTransparencyChange,
    )

    Spacer(Modifier.height(SyMetric.gapSm))
    Text(stringResource(R.string.settings_advanced_motion),
        style = DsType.std14Strong.withReadingWeight(), color = colors.text)
    AppearanceAdvancedSlider(
        stringResource(R.string.settings_advanced_motion_tempo),
        settings.motionDurationScale, .7f..1.3f, onMotionChange,
    )

    Spacer(Modifier.height(SyMetric.gapSm))
    Text(stringResource(R.string.settings_advanced_components),
        style = DsType.std14Strong.withReadingWeight(), color = colors.text)
    AppearanceAdvancedSlider(
        stringResource(R.string.settings_advanced_emphasis), settings.componentEmphasis, 0f..1f,
        onEmphasisChange,
    )
    AppearanceChip(stringResource(R.string.settings_advanced_reset), false, onReset)
}

@Composable
private fun AppearanceAdvancedSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalSyColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
        Text("${(value * 100).toInt()}%",
            style = DsType.small13Strong.withReadingWeight(), color = colors.text)
    }
    SySlider(
        value = value, onValueChange = onChange,
        valueRange = range, steps = 5, hapticSegments = 6,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun AppearanceRow(settings: AppSettings, onSelect: (String) -> Unit) {
    // Screen 26: theme modes are a four-position choice track, not four unrelated
    // preview cards. Real previews remain in AppearanceReadingPreview below.
    SySegmented(
        segments = listOf(
            SySegment("light", stringResource(R.string.settings_appearance_light)),
            SySegment("dark", stringResource(R.string.settings_appearance_dark)),
            SySegment("matte_black", stringResource(R.string.settings_appearance_matte_black)),
            SySegment("system", stringResource(R.string.settings_appearance_system)),
        ),
        selectedKey = settings.themePreference,
        onSelect = onSelect,
        role = Role.RadioButton,
        hapticOnChange = true,
        stretch = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun AccentThemeRow(settings: AppSettings, onSelect: (String) -> Unit) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Column(modifier = Modifier.padding(vertical = SyMetric.gapMd)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            val resolvedAccentKey = AccentPalettes.of(settings.accentTheme).key
            AccentPalettes.ALL.forEach { palette ->
                val selected = resolvedAccentKey == palette.key
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(68.dp)
                        .clip(DsShapes.cube)
                        .background(
                            if (selected) colors.selected else Color.Transparent,
                            DsShapes.cube,
                        )
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) colors.primary else colors.border,
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
                    Spacer(Modifier.height(SyMetric.gapSm))
                    Text(
                        palette.cnName,
                        style = DsType.caption11.withReadingWeight(),
                        color = if (selected) colors.primary else colors.textTertiary,
                    )
                    if (selected) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = colors.primary,
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
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Box(
        modifier = Modifier
            .heightIn(min = SyMetric.touch)
            .clip(DsShapes.cube)
            .background(if (selected) colors.selected else Color.Transparent)
            .border(1.dp, colors.border.copy(alpha = .6f), DsShapes.cube)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = SyMetric.gapMd),
    ) {
        Text(
            label,
            style = DsType.small13.withReadingWeight(),
            color = if (selected) colors.primary else colors.textSecondary,
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
    onPick: (Uri) -> Unit,
    onClear: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    Column(modifier = Modifier.padding(vertical = SyMetric.gapMd)) {
        if (path != null) {
            val imagePath = path
            val thumbnail by produceState<ImageBitmap?>(initialValue = null, key1 = imagePath) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(imagePath, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                        // Wide/panoramic images must be sampled by width as well as height.
                        // The thumbnail is only 96 x 56 dp, not a full-size bitmap.
                        val sample = wallpaperDecodeSample(bounds.outWidth, bounds.outHeight, 256)
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
                Spacer(Modifier.height(SyMetric.gapMd))
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
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(SyMetric.gapMd))
        Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            AppearanceChip(stringResource(R.string.settings_background_image_choose), false) {
                picker.launch(arrayOf("image/*"))
            }
            if (path != null) {
                AppearanceChip(stringResource(R.string.settings_background_image_clear), false) {
                    onClear()
                }
            }
        }
    }
}
