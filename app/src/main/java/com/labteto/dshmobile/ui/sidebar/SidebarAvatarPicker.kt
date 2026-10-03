package com.labteto.dshmobile.ui.sidebar

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaPresetCatalog
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.rememberSidebarAvatarStore
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared app-identity avatar used by every navigation drawer. */
@Composable
internal fun SidebarAvatarPicker(modifier: Modifier = Modifier) {
    val store = rememberSidebarAvatarStore()
    val rawSource by store.source.collectAsStateWithLifecycle(initialValue = null)
    val source = remember(rawSource) { parseSidebarAvatarSource(rawSource) }
    val scope = rememberCoroutineScope()
    var sheetOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveFailedText = stringResource(R.string.sidebar_avatar_save_failed)
    val setFailedText = stringResource(R.string.sidebar_avatar_set_failed)
    val resetFailedText = stringResource(R.string.sidebar_avatar_reset_failed)

    val customPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { store.importCustom(uri) }
                .onSuccess { error = null }
                .onFailure {
                    error = it.message ?: saveFailedText
                    sheetOpen = true
                }
            busy = false
        }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = !busy) {
                error = null
                sheetOpen = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = DsTheme.colors.characterAccentTertiary,
            border = BorderStroke(1.dp, DsTheme.colors.characterAccent.copy(alpha = 0.55f)),
        ) {
            SidebarAvatarImage(source = source, contentDescription = stringResource(R.string.sidebar_avatar_open))
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).size(17.dp),
            shape = CircleShape,
            color = DsTheme.colors.accent,
            border = BorderStroke(1.5.dp, DsTheme.colors.sidebar),
        ) {
            Icon(
                FeatherIcons.Image,
                contentDescription = null,
                tint = DsTheme.colors.onAccent,
                modifier = Modifier.padding(3.5.dp),
            )
        }
    }

    if (sheetOpen) {
        SidebarAvatarSheet(
            selected = rawSource,
            busy = busy,
            error = error,
            onDismiss = { if (!busy) sheetOpen = false },
            onPreset = { preset ->
                val path = preset.artwork?.assetPath ?: return@SidebarAvatarSheet
                busy = true
                scope.launch {
                    runCatching { store.selectBundled(path) }
                        .onSuccess {
                            error = null
                            sheetOpen = false
                        }
                        .onFailure { error = it.message ?: setFailedText }
                    busy = false
                }
            },
            onCustom = {
                sheetOpen = false
                customPicker.launch(arrayOf("image/*"))
            },
            onReset = {
                busy = true
                scope.launch {
                    runCatching { store.reset() }
                        .onSuccess {
                            error = null
                            sheetOpen = false
                        }
                        .onFailure { error = it.message ?: resetFailedText }
                    busy = false
                }
            },
        )
    }
}

@Composable
private fun SidebarAvatarSheet(
    selected: String?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPreset: (PersonaPreset) -> Unit,
    onCustom: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = DsTheme.colors
    val presets = remember {
        PersonaPresetCatalog.presets.filter { it.artwork != null }
    }
    DsBottomSheet(
        title = stringResource(R.string.sidebar_avatar_title),
        subtitle = stringResource(R.string.sidebar_avatar_subtitle),
        onDismiss = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsButton(
                text = stringResource(R.string.sidebar_avatar_custom),
                onClick = onCustom,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                icon = FeatherIcons.Image,
                variant = DsButtonVariant.Info,
            )
            DsButton(
                text = stringResource(R.string.sidebar_avatar_reset),
                onClick = onReset,
                enabled = !busy && !selected.isNullOrBlank(),
                size = DsButtonSize.Normal,
                variant = DsButtonVariant.Ghost,
            )
        }
        error?.let {
            Text(
                text = it,
                style = DsType.small13.withReadingWeight(),
                color = colors.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.tiny),
            )
        }
        Text(
            text = stringResource(R.string.sidebar_avatar_presets),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.labelSecondary,
            modifier = Modifier.padding(top = DsSpacing.xsmall),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(68.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            items(presets, key = PersonaPreset::id) { preset ->
                val assetPath = checkNotNull(preset.artwork).assetPath
                val isSelected = selected == bundledSidebarAvatarSource(assetPath)
                Surface(
                    onClick = { onPreset(preset) },
                    enabled = !busy,
                    shape = DsShapes.block,
                    color = if (isSelected) colors.characterAccentTertiary else colors.sidebarNavHover,
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) colors.characterAccent else colors.borderL1,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(DsSpacing.xsmall),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                    ) {
                        Surface(
                            modifier = Modifier.size(52.dp),
                            shape = CircleShape,
                            color = colors.characterAccentTertiary,
                        ) {
                            SidebarAvatarImage(
                                source = SidebarAvatarSource.Bundled(assetPath),
                                contentDescription = preset.persona.name,
                            )
                        }
                        Text(
                            text = preset.persona.name,
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarAvatarImage(source: SidebarAvatarSource, contentDescription: String) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val imageSource = when (source) {
                    SidebarAvatarSource.Default -> return@runCatching null
                    is SidebarAvatarSource.Bundled -> ImageDecoder.createSource(context.assets, source.assetPath)
                    is SidebarAvatarSource.Custom -> {
                        val root = File(context.filesDir, "ui/sidebar-avatars").canonicalFile
                        val file = source.file.canonicalFile
                        if (!file.isFile || !file.path.startsWith(root.path + File.separator)) {
                            return@runCatching null
                        }
                        ImageDecoder.createSource(file)
                    }
                }
                ImageDecoder.decodeDrawable(imageSource) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    val scale = maxOf(width, height).toFloat() / AVATAR_DECODE_EDGE
                    if (scale > 1f) {
                        decoder.setTargetSize(
                            (width / scale).toInt().coerceAtLeast(1),
                            (height / scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
            }.getOrNull()
        }
    }

    DisposableEffect(drawable) {
        val animated = drawable as? AnimatedImageDrawable
        animated?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        animated?.start()
        onDispose { animated?.stop() }
    }

    if (drawable == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(DsTheme.colors.characterAccentTertiary, DsTheme.colors.sidebarNavAccent),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.sidebar_avatar_default_mark),
                style = DsType.base16Strong.withReadingWeight(),
                color = DsTheme.colors.characterAccent,
            )
        }
    } else {
        AndroidView(
            factory = { viewContext ->
                ImageView(viewContext).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            },
            update = { imageView ->
                imageView.contentDescription = contentDescription
                imageView.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.start()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val AVATAR_DECODE_EDGE = 256f

