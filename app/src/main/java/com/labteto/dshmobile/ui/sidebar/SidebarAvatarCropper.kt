package com.labteto.dshmobile.ui.sidebar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun SidebarAvatarCropper(
    prepared: PreparedSidebarAvatar,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: (SidebarAvatarCrop) -> Unit,
) {
    var crop by remember(prepared.file.absolutePath) {
        mutableStateOf(SidebarAvatarCrop())
    }
    var viewport by remember(prepared.file.absolutePath) {
        mutableStateOf(IntSize.Zero)
    }
    val previewSource = remember(prepared.file.absolutePath, crop) {
        SidebarAvatarSource.Custom(prepared.file, crop)
    }

    DsBottomSheet(
        title = stringResource(R.string.sidebar_avatar_crop_title),
        subtitle = stringResource(R.string.sidebar_avatar_crop_subtitle),
        onDismiss = { if (!busy) onCancel() },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 300.dp)
                    .aspectRatio(1f)
                    .onSizeChanged { viewport = it }
                    .pointerInput(prepared.file.absolutePath, prepared.width, prepared.height, busy) {
                        if (busy) return@pointerInput
                        detectTransformGestures { _, pan, zoom, _ ->
                            crop = updateSidebarAvatarCropFromGesture(
                                current = crop,
                                pan = pan,
                                zoomChange = zoom,
                                viewport = viewport,
                                sourceWidth = prepared.width,
                                sourceHeight = prepared.height,
                            )
                        }
                    },
                shape = CircleShape,
                color = DsTheme.colors.characterAccentTertiary,
                border = BorderStroke(2.dp, DsTheme.colors.characterAccent),
            ) {
                SidebarAvatarImage(
                    source = previewSource,
                    contentDescription = stringResource(R.string.sidebar_avatar_crop_preview),
                )
            }
        }

        Text(
            text = stringResource(R.string.sidebar_avatar_crop_gesture_hint),
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelSecondary,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.sidebar_avatar_crop_zoom),
            style = DsType.small13Strong.withReadingWeight(),
            color = DsTheme.colors.labelPrimary,
        )
        Slider(
            value = crop.zoom,
            onValueChange = { zoom ->
                if (!busy) {
                    crop = normalizeSidebarAvatarCropForSource(
                        crop.copy(zoom = zoom),
                        sourceWidth = prepared.width,
                        sourceHeight = prepared.height,
                    )
                }
            },
            valueRange = SIDEBAR_AVATAR_MIN_ZOOM..SIDEBAR_AVATAR_MAX_ZOOM,
            enabled = !busy,
        )

        error?.let {
            Text(
                text = it,
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                variant = DsButtonVariant.Ghost,
            )
            DsButton(
                text = stringResource(R.string.sidebar_avatar_crop_confirm),
                onClick = { onConfirm(crop) },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }
    }
}

internal fun updateSidebarAvatarCropFromGesture(
    current: SidebarAvatarCrop,
    pan: Offset,
    zoomChange: Float,
    viewport: IntSize,
    sourceWidth: Int,
    sourceHeight: Int,
): SidebarAvatarCrop {
    if (viewport.width <= 0 || viewport.height <= 0) {
        return normalizeSidebarAvatarCropForSource(current, sourceWidth, sourceHeight)
    }
    val safeZoom = zoomChange.takeIf { it.isFinite() && it > 0f } ?: 1f
    val next = current.copy(
        zoom = current.zoom * safeZoom,
        offsetX = current.offsetX + pan.x / viewport.width.toFloat(),
        offsetY = current.offsetY + pan.y / viewport.height.toFloat(),
    )
    return normalizeSidebarAvatarCropForSource(next, sourceWidth, sourceHeight)
}

internal fun normalizeSidebarAvatarCropForSource(
    crop: SidebarAvatarCrop,
    sourceWidth: Int,
    sourceHeight: Int,
): SidebarAvatarCrop {
    val normalized = crop.normalized()
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        return normalized.copy(offsetX = 0f, offsetY = 0f)
    }

    val baseScale = maxOf(
        1f / sourceWidth.toFloat(),
        1f / sourceHeight.toFloat(),
    )
    val scaledWidth = sourceWidth * baseScale * normalized.zoom
    val scaledHeight = sourceHeight * baseScale * normalized.zoom
    val maxOffsetX = maxOf((scaledWidth - 1f) / 2f, 0f)
    val maxOffsetY = maxOf((scaledHeight - 1f) / 2f, 0f)

    return normalized.copy(
        offsetX = normalized.offsetX.coerceIn(-maxOffsetX, maxOffsetX),
        offsetY = normalized.offsetY.coerceIn(-maxOffsetY, maxOffsetY),
    )
}

internal fun calculateSidebarAvatarCustomCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    crop: SidebarAvatarCrop,
): SidebarAvatarCropTransform? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
        return null
    }

    val normalized = normalizeSidebarAvatarCropForSource(crop, sourceWidth, sourceHeight)
    val baseScale = maxOf(
        targetWidth.toFloat() / sourceWidth.toFloat(),
        targetHeight.toFloat() / sourceHeight.toFloat(),
    )
    val scale = baseScale * normalized.zoom
    val scaledWidth = sourceWidth * scale
    val scaledHeight = sourceHeight * scale
    val centeredX = (targetWidth - scaledWidth) / 2f
    val centeredY = (targetHeight - scaledHeight) / 2f
    val minTranslateX = minOf(targetWidth - scaledWidth, 0f)
    val minTranslateY = minOf(targetHeight - scaledHeight, 0f)

    return SidebarAvatarCropTransform(
        scale = scale,
        translateX = (centeredX + normalized.offsetX * targetWidth)
            .coerceIn(minTranslateX, 0f),
        translateY = (centeredY + normalized.offsetY * targetHeight)
            .coerceIn(minTranslateY, 0f),
    )
}
