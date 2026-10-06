package com.labteto.dshmobile.ui.sidebar

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
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
import com.labteto.dshmobile.local.presentation.PersonaPresetCatalog
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
    var pendingCustom by remember { mutableStateOf<PreparedSidebarAvatar?>(null) }
    val saveFailedText = stringResource(R.string.sidebar_avatar_save_failed)
    val setFailedText = stringResource(R.string.sidebar_avatar_set_failed)
    val resetFailedText = stringResource(R.string.sidebar_avatar_reset_failed)

    val customPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            sheetOpen = true
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            runCatching { store.prepareCustom(uri) }
                .onSuccess { prepared ->
                    pendingCustom = prepared
                    error = null
                }
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

    pendingCustom?.let { prepared ->
        SidebarAvatarCropper(
            prepared = prepared,
            busy = busy,
            error = error,
            onCancel = {
                pendingCustom = null
                error = null
                sheetOpen = true
                scope.launch { store.discardCustom(prepared) }
            },
            onConfirm = { crop ->
                busy = true
                scope.launch {
                    runCatching { store.saveCustom(prepared, crop) }
                        .onSuccess {
                            pendingCustom = null
                            error = null
                        }
                        .onFailure { error = it.message ?: saveFailedText }
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
internal fun SidebarAvatarImage(
    source: SidebarAvatarSource,
    contentDescription: String,
    decodeEdge: Float = AVATAR_DECODE_EDGE,
) {
    val context = LocalContext.current
    val mediaKey = when (source) {
        SidebarAvatarSource.Default -> "default"
        is SidebarAvatarSource.Bundled -> "asset:${source.assetPath}"
        is SidebarAvatarSource.Custom -> "file:${source.file.absolutePath}"
    }
    val drawable by produceState<Drawable?>(initialValue = null, mediaKey, decodeEdge) {
        value = withContext(Dispatchers.IO) {
            if (source is SidebarAvatarSource.Default) return@withContext null
            runCatching {
                val imageSource = when (source) {
                    SidebarAvatarSource.Default -> error("default avatar has no image source")
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
                    val scale = maxOf(width, height).toFloat() / decodeEdge.coerceAtLeast(AVATAR_DECODE_EDGE)
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
            factory = { viewContext -> SidebarAvatarImageView(viewContext) },
            update = { imageView ->
                imageView.contentDescription = contentDescription
                imageView.customCrop = (source as? SidebarAvatarSource.Custom)?.crop
                imageView.focusY = when (source) {
                    is SidebarAvatarSource.Bundled -> BUNDLED_AVATAR_FOCUS_Y
                    else -> CENTERED_AVATAR_FOCUS_Y
                }
                imageView.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.start()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private class SidebarAvatarImageView(context: Context) : AppCompatImageView(context) {
    var customCrop: SidebarAvatarCrop? = null
        set(value) {
            if (field == value) return
            field = value
            updateCropMatrix()
        }

    var focusY: Float = CENTERED_AVATAR_FOCUS_Y
        set(value) {
            val normalized = value.coerceIn(0f, 1f)
            if (field == normalized) return
            field = normalized
            updateCropMatrix()
        }

    init {
        scaleType = ImageView.ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        updateCropMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCropMatrix()
    }

    private fun updateCropMatrix() {
        val currentDrawable = drawable ?: return
        val transform = customCrop?.let { crop ->
            calculateSidebarAvatarCustomCrop(
                sourceWidth = currentDrawable.intrinsicWidth,
                sourceHeight = currentDrawable.intrinsicHeight,
                targetWidth = width,
                targetHeight = height,
                crop = crop,
            )
        } ?: calculateSidebarAvatarCrop(
            sourceWidth = currentDrawable.intrinsicWidth,
            sourceHeight = currentDrawable.intrinsicHeight,
            targetWidth = width,
            targetHeight = height,
            focusY = focusY,
        )
        if (transform == null) {
            scaleType = ImageView.ScaleType.CENTER_CROP
            return
        }

        if (scaleType != ImageView.ScaleType.MATRIX) {
            scaleType = ImageView.ScaleType.MATRIX
        }
        imageMatrix = Matrix().apply {
            setScale(transform.scale, transform.scale)
            postTranslate(transform.translateX, transform.translateY)
        }
    }
}

internal data class SidebarAvatarCropTransform(
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
)

internal fun calculateSidebarAvatarCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    focusY: Float,
): SidebarAvatarCropTransform? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
        return null
    }

    val scale = maxOf(
        targetWidth.toFloat() / sourceWidth.toFloat(),
        targetHeight.toFloat() / sourceHeight.toFloat(),
    )
    val scaledWidth = sourceWidth * scale
    val scaledHeight = sourceHeight * scale
    val translateX = (targetWidth - scaledWidth) / 2f
    val minTranslateY = minOf(targetHeight - scaledHeight, 0f)
    val desiredTranslateY = targetHeight / 2f - scaledHeight * focusY.coerceIn(0f, 1f)
    val translateY = desiredTranslateY.coerceIn(minTranslateY, 0f)

    return SidebarAvatarCropTransform(
        scale = scale,
        translateX = translateX,
        translateY = translateY,
    )
}

// 内置人物图统一使用竖构图，头像裁切向上偏置，优先保留头部与面部。
private const val BUNDLED_AVATAR_FOCUS_Y = 0.38f
private const val CENTERED_AVATAR_FOCUS_Y = 0.5f
private const val AVATAR_DECODE_EDGE = 256f

