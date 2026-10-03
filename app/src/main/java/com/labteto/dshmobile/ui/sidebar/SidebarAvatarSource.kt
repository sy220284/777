package com.labteto.dshmobile.ui.sidebar

import java.io.File

internal sealed interface SidebarAvatarSource {
    data object Default : SidebarAvatarSource

    data class Bundled(val assetPath: String) : SidebarAvatarSource

    data class Custom(val file: File) : SidebarAvatarSource
}

internal const val SIDEBAR_AVATAR_ASSET_PREFIX = "asset:"

internal fun bundledSidebarAvatarSource(assetPath: String): String {
    val normalized = assetPath.trim()
    require(isSafeSidebarAvatarAsset(normalized)) { "头像预置路径无效" }
    return SIDEBAR_AVATAR_ASSET_PREFIX + normalized
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
    val file = File(value)
    return if (file.isAbsolute) SidebarAvatarSource.Custom(file) else SidebarAvatarSource.Default
}

internal fun isSafeSidebarAvatarAsset(path: String): Boolean =
    path.startsWith("persona-presets/") &&
        !path.contains("..") &&
        path.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")

