package com.labteto.dshmobile.ui.sidebar

import java.io.File

internal data class SidebarAvatarCrop(
    val zoom: Float = SIDEBAR_AVATAR_MIN_ZOOM,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun normalized(): SidebarAvatarCrop = SidebarAvatarCrop(
        zoom = zoom.takeIf { it.isFinite() }
            ?.coerceIn(SIDEBAR_AVATAR_MIN_ZOOM, SIDEBAR_AVATAR_MAX_ZOOM)
            ?: SIDEBAR_AVATAR_MIN_ZOOM,
        offsetX = offsetX.takeIf { it.isFinite() }
            ?.coerceIn(-SIDEBAR_AVATAR_MAX_OFFSET, SIDEBAR_AVATAR_MAX_OFFSET)
            ?: 0f,
        offsetY = offsetY.takeIf { it.isFinite() }
            ?.coerceIn(-SIDEBAR_AVATAR_MAX_OFFSET, SIDEBAR_AVATAR_MAX_OFFSET)
            ?: 0f,
    )
}

internal sealed interface SidebarAvatarSource {
    data object Default : SidebarAvatarSource

    data class Bundled(val assetPath: String) : SidebarAvatarSource

    data class Custom(
        val file: File,
        val crop: SidebarAvatarCrop = SidebarAvatarCrop(),
    ) : SidebarAvatarSource
}

internal const val SIDEBAR_AVATAR_ASSET_PREFIX = "asset:"
internal const val SIDEBAR_AVATAR_CUSTOM_PREFIX = "custom:v1:"
internal const val SIDEBAR_AVATAR_MIN_ZOOM = 1f
internal const val SIDEBAR_AVATAR_MAX_ZOOM = 4f
private const val SIDEBAR_AVATAR_MAX_OFFSET = 4f

internal fun bundledSidebarAvatarSource(assetPath: String): String {
    val normalized = assetPath.trim()
    require(isSafeSidebarAvatarAsset(normalized)) { "头像预置路径无效" }
    return SIDEBAR_AVATAR_ASSET_PREFIX + normalized
}

internal fun customSidebarAvatarSource(file: File, crop: SidebarAvatarCrop): String {
    require(file.isAbsolute) { "Custom avatar path must be absolute" }
    val normalized = crop.normalized()
    return buildString {
        append(SIDEBAR_AVATAR_CUSTOM_PREFIX)
        append(normalized.zoom)
        append(':')
        append(normalized.offsetX)
        append(':')
        append(normalized.offsetY)
        append(':')
        append(file.absolutePath)
    }
}

internal fun parseSidebarAvatarSource(raw: String?): SidebarAvatarSource {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return SidebarAvatarSource.Default
    if (value.startsWith(SIDEBAR_AVATAR_ASSET_PREFIX)) {
        val path = value.removePrefix(SIDEBAR_AVATAR_ASSET_PREFIX)
        return if (isSafeSidebarAvatarAsset(path)) {
            SidebarAvatarSource.Bundled(path)
        } else {
            SidebarAvatarSource.Default
        }
    }
    if (value.startsWith(SIDEBAR_AVATAR_CUSTOM_PREFIX)) {
        val parts = value.removePrefix(SIDEBAR_AVATAR_CUSTOM_PREFIX).split(':', limit = 4)
        if (parts.size != 4) return SidebarAvatarSource.Default
        val file = File(parts[3])
        if (!file.isAbsolute) return SidebarAvatarSource.Default
        val crop = SidebarAvatarCrop(
            zoom = parts[0].toFloatOrNull() ?: return SidebarAvatarSource.Default,
            offsetX = parts[1].toFloatOrNull() ?: return SidebarAvatarSource.Default,
            offsetY = parts[2].toFloatOrNull() ?: return SidebarAvatarSource.Default,
        ).normalized()
        return SidebarAvatarSource.Custom(file = file, crop = crop)
    }
    val file = File(value)
    return if (file.isAbsolute) {
        SidebarAvatarSource.Custom(file = file)
    } else {
        SidebarAvatarSource.Default
    }
}

internal fun isSafeSidebarAvatarAsset(path: String): Boolean =
    path.startsWith("persona-presets/") &&
        !path.contains("..") &&
        path.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")
