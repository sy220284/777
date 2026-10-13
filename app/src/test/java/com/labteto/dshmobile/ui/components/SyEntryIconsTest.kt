package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyEntryIconsTest {
    private val icons: List<ImageVector> = listOf(
        SyEntryIcons.Persona,
        SyEntryIcons.NewSession,
        SyEntryIcons.Projects,
        SyEntryIcons.Tasks,
        SyEntryIcons.Settings,
        SyEntryIcons.Search,
        SyEntryIcons.Gallery,
        SyEntryIcons.GroupChat,
        SyEntryIcons.Diary,
        SyEntryIcons.WorkspaceFiles,
        SyEntryIcons.RunCenter,
        SyEntryIcons.Tools,
        SyEntryIcons.Usage,
        SyEntryIcons.Reader,
        SyEntryIcons.Quota,
        SyEntryIcons.Appearance,
        SyEntryIcons.Memory,
        SyEntryIcons.SessionStorage,
        SyEntryIcons.Models,
        SyEntryIcons.Performance,
        SyEntryIcons.Account,
        SyEntryIcons.Notifications,
        SyEntryIcons.Permissions,
        SyEntryIcons.Advanced,
        SyEntryIcons.Updates,
        SyEntryIcons.ChatStyle
    )

    @Test fun `each v6 semantic icon has intentional visible vector geometry`() {
        assertEquals(26, icons.size)
        icons.forEach { icon ->
            assertEquals(24f, icon.viewportWidth, 0f)
            assertEquals(24f, icon.viewportHeight, 0f)
            assertTrue(icon.name, icon.root.size >= 1)
            icon.root.forEach { node ->
                val path = node as VectorPath
                assertTrue(icon.name, path.pathData.size > 1)
                assertEquals(1.75f, path.strokeLineWidth, 0f)
                assertEquals(StrokeCap.Round, path.strokeLineCap)
                assertEquals(StrokeJoin.Round, path.strokeLineJoin)
                assertEquals(null, path.fill)
            }
        }
    }

    @Test fun `vector cache remains stable for scrolling sidebars`() {
        assertTrue(SyEntryIcons.Persona === SyEntryIcons.Persona)
        assertTrue(SyEntryIcons.Settings === SyEntryIcons.Settings)
    }
}
