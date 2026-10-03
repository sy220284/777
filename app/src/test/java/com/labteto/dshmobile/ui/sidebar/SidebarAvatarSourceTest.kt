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
    fun customSourceRoundTripsCropWithoutRewritingImage() {
        val file = File("/data/user/0/com.sy220284.dshmobile/files/ui/sidebar-avatars/avatar.gif")
        val crop = SidebarAvatarCrop(
            zoom = 2.25f,
            offsetX = -0.18f,
            offsetY = 0.27f,
        )

        val parsed = parseSidebarAvatarSource(customSidebarAvatarSource(file, crop))

        assertEquals(
            SidebarAvatarSource.Custom(file = file, crop = crop),
            parsed,
        )
    }

    @Test
    fun absoluteFilePathRemainsBackwardCompatibleWithCenteredCrop() {
        val file = File("/data/user/0/com.sy220284.dshmobile/files/ui/sidebar-avatars/avatar.gif")
        val parsed = parseSidebarAvatarSource(file.absolutePath)

        assertTrue(parsed is SidebarAvatarSource.Custom)
        assertEquals(file, (parsed as SidebarAvatarSource.Custom).file)
        assertEquals(SidebarAvatarCrop(), parsed.crop)
    }

    @Test
    fun malformedCustomSourceFallsBackToDefault() {
        assertEquals(
            SidebarAvatarSource.Default,
            parseSidebarAvatarSource("custom:v1:bad:0:0:/data/avatar.webp"),
        )
        assertEquals(
            SidebarAvatarSource.Default,
            parseSidebarAvatarSource("custom:v1:1:0:0:relative.webp"),
        )
    }

    @Test
    fun relativeFilePathFallsBackToDefault() {
        assertEquals(
            SidebarAvatarSource.Default,
            parseSidebarAvatarSource("downloads/avatar.gif"),
        )
    }
}
