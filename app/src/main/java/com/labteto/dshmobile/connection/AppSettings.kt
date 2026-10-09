package com.labteto.dshmobile.connection

/** Local application preferences persisted through DataStore. */
data class AppSettings(
    val notifyTurnComplete: Boolean = true,
    val notifyGoal: Boolean = true,
    val notifyNeedsAction: Boolean = true,
    val notifyLocalJobs: Boolean = true,
    val themePreference: String = "system", // light | dark | matte_black | system
    val accentTheme: String = "celadon", // 中国风传统色卡 key，见 ui/theme/AccentPalettes
    /**
     * Absolute path of the copied background image inside app storage, or null for the plain theme
     * colour. The name of the file in [com.labteto.dshmobile.ui.theme.APP_BACKGROUND_DIR] is not
     * stored instead of the path because the path is what `BitmapFactory` wants, and `filesDir`
     * does not move for an installed app.
     */
    val backgroundImagePath: String? = null,
    /**
     * Source selected for the app identity shown beside “神言神语” in every navigation drawer.
     *
     * `asset:` values point at bundled persona artwork; absolute paths point at a validated copy
     * inside app-private storage. Keeping one source in app settings makes the chat and local-work
     * drawers projections of the same durable choice.
     */
    val sidebarAvatarSource: String? = null,
    /**
     * Keeps custom backgrounds readable by applying a theme-aware veil behind the app surface.
     * Defaults on so existing users benefit immediately after upgrading.
     */
    val backgroundAdaptiveContrast: Boolean = true,
    /** User reading scale layered on top of Android's system font scale. */
    val textScale: Float = 1.0f,
    /** 0=standard, 1=slightly bold, 2=bold. Applied across semantic app text roles while preserving hierarchy. */
    val textWeightAdjustment: Int = 0,
    /** 0=clearer/more opaque, 1=more transparent; 0.5 preserves the previous visual baseline. */
    val wallpaperSurfaceTransparency: Float = 0.5f,
)

