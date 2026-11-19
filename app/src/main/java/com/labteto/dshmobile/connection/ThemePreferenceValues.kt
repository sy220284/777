package com.labteto.dshmobile.connection

/** 唯一的外观存储值入口；历史深色偏好自动迁移为磨砂黑。 */
internal object ThemePreferenceValues {
    const val LIGHT = "light"
    const val MATTE_BLACK = "matte_black"
    const val SYSTEM = "system"

    fun normalize(raw: String?): String = when (raw?.lowercase()) {
        LIGHT -> LIGHT
        "dark", MATTE_BLACK -> MATTE_BLACK
        else -> SYSTEM
    }
}
