package com.labteto.dshmobile.ui.sidebar

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarAvatarSourceTest {
    @Test
    fun blankSourceUsesDefaultAvatar() {
        assertEquals(SidebarAvatarSource.Default, parseSidebarAvatarSource(null))
        assertEquals(SidebarAvatarSource.Default, parseSidebarAvatarSource("  "))
    }

    @Test
    fun bundledSourceRoundTripsSafePresetPath() {
        val path = "persona-presets/genshin-klee.webp"
        val encoded = bundledSidebarAvatarSource(path)

        assertEquals(
            SidebarAvatarSource.Bundled(path),
            parseSidebarAvatarSource(encoded),
        )
    }

    @Test
    fun bundledSourceRejectsTraversalAndUnknownDirectories() {
        assertEquals(
            SidebarAvatarSource.Default,
            parseSidebarAvatarSource("asset:persona-presets/../secret.webp"),
        )
        assertEquals(
            SidebarAvatarSource.Default,
            parseSidebarAvatarSource("asset:downloads/avatar.webp"),
        )
    }

    @Test
    fun absoluteFilePathBecomesCustomSource() {
        val file = File("/data/user/0/com.sy220284.dshmobile/files/ui/sidebar-avatars/avatar.gif")
        val parsed = parseSidebarAvatarSource(file.absolutePath)

        assertTrue(parsed is SidebarAvatarSource.Custom)
        assertEquals(file, (parsed as SidebarAvatarSource.Custom).file)
    }

    @Test
    fun relativeFilePathFallsBackToDefault() {
        assertEquals(
            SidebarAvatarSource.Default,
            parseSidebarAvatarSource("downloads/avatar.gif"),
        )
    }
}

