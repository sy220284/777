package com.labteto.dshmobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real Compose ImageVectors must instantiate successfully, not merely exist in the SVG catalog.
 * This exercises every SVG PathParser source used by the live feature-entry icons.
 */
class PaperInkEntryIconsTest {
    @Test
    fun allActiveAndSupplementalEntryPathsCanBeRendered() {
        val icons = linkedMapOf(
            "Persona" to SyEntryIcons.Persona,
            "NewSession" to SyEntryIcons.NewSession,
            "Tasks" to SyEntryIcons.Tasks,
            "Settings" to SyEntryIcons.Settings,
            "Search" to SyEntryIcons.Search,
            "Gallery" to SyEntryIcons.Gallery,
            "GroupChat" to SyEntryIcons.GroupChat,
            "Diary" to SyEntryIcons.Diary,
            "WorkspaceFiles" to SyEntryIcons.WorkspaceFiles,
            "Tools" to SyEntryIcons.Tools,
            "Usage" to SyEntryIcons.Usage,
            "Reader" to SyEntryIcons.Reader,
            "Quota" to SyEntryIcons.Quota,
            "Appearance" to SyEntryIcons.Appearance,
            "Memory" to SyEntryIcons.Memory,
            "SessionStorage" to SyEntryIcons.SessionStorage,
            "Models" to SyEntryIcons.Models,
            "Performance" to SyEntryIcons.Performance,
            "Account" to SyEntryIcons.Account,
            "Notifications" to SyEntryIcons.Notifications,
            "Permissions" to SyEntryIcons.Permissions,
            "Advanced" to SyEntryIcons.Advanced,
            "Updates" to SyEntryIcons.Updates,
            "ChatStyle" to SyEntryIcons.ChatStyle,
            "Connections" to SyEntryIcons.Connections,
            "Skills" to SyEntryIcons.Skills,
            "Plugins" to SyEntryIcons.Plugins,
        )
        assertEquals(27, icons.size)
        icons.forEach { (name, icon) ->
            assertEquals("SyEntry.$name", icon.name)
            assertEquals(24f, icon.viewportWidth, 0.01f)
            assertEquals(24f, icon.viewportHeight, 0.01f)
        }
    }
}
