package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavigationTest {
    @Test fun directConfigurationEntriesReturnToTheirCaller() {
        listOf(SettingsDestination.MODELS, SettingsDestination.PERMISSIONS,
            SettingsDestination.MEMORY_MANAGEMENT, SettingsDestination.USAGE,
            SettingsDestination.MINIMAX_QUOTA, SettingsDestination.ACCOUNT).forEach { entry ->
            assertNull("Direct entry $entry must close the feature", settingsBackDestination(entry, entry))
        }
    }

    @Test fun ordinarySettingsBrowseReturnsThroughItsParent() {
        assertEquals(SettingsDestination.ROOT,
            settingsBackDestination(SettingsDestination.MODELS, SettingsDestination.ROOT))
        assertEquals(SettingsDestination.MEMORY,
            settingsBackDestination(SettingsDestination.MEMORY_MANAGEMENT, SettingsDestination.ROOT))
        assertNull(settingsBackDestination(SettingsDestination.ROOT, SettingsDestination.ROOT))
    }

    @Test fun nestedDirectEntryUnwindsBeforeClosingItsCaller() {
        assertEquals(SettingsDestination.MEMORY,
            settingsBackDestination(SettingsDestination.MEMORY_MANAGEMENT, SettingsDestination.MEMORY))
        assertNull(settingsBackDestination(SettingsDestination.MEMORY, SettingsDestination.MEMORY))
    }
}
