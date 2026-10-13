package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavigationTest {
    @Test fun directConfigurationEntriesReturnToTheirCaller() {
        listOf(SettingsDestination.MODELS, SettingsDestination.PERMISSIONS,
            SettingsDestination.MEMORY_MANAGEMENT, SettingsDestination.USAGE,
            SettingsDestination.MINIMAX_QUOTA, SettingsDestination.ACCOUNT).forEach { entry ->
            assertNull("Direct entry $entry must close the feature", settingsNavigationPop(listOf(entry)))
        }
    }

    @Test fun ordinarySettingsBrowseReturnsThroughItsVisitedParents() {
        val root = listOf(SettingsDestination.ROOT)
        val memory = settingsNavigationPush(root, SettingsDestination.MEMORY)
        val management = settingsNavigationPush(memory, SettingsDestination.MEMORY_MANAGEMENT)
        assertEquals(memory, settingsNavigationPop(management))
        assertEquals(root, settingsNavigationPop(memory))
        assertNull(settingsNavigationPop(root))
    }

    @Test fun nestedDirectEntryUnwindsBeforeClosingItsCaller() {
        val entry = listOf(SettingsDestination.MEMORY)
        val nested = settingsNavigationPush(entry, SettingsDestination.MEMORY_MANAGEMENT)
        assertEquals(entry, settingsNavigationPop(nested))
        assertNull(settingsNavigationPop(entry))
    }

    @Test fun quotaConfigurationAndCrossLinksKeepTheirActualSource() {
        listOf(
            SettingsDestination.MINIMAX_QUOTA to SettingsDestination.MODELS,
            SettingsDestination.USAGE to SettingsDestination.PRICING,
            SettingsDestination.MODELS to SettingsDestination.MODEL_USAGE,
            SettingsDestination.ADVANCED to SettingsDestination.SESSION_STORAGE,
            SettingsDestination.ADVANCED to SettingsDestination.SESSION,
        ).forEach { (source, target) ->
            val entry = listOf(source)
            val nested = settingsNavigationPush(entry, target)
            assertEquals(listOf(source, target), nested)
            assertEquals(entry, settingsNavigationPop(nested))
            assertNull(settingsNavigationPop(entry))
        }
    }

    @Test fun usageDetailsReturnToTheLogOrSummaryThatOpenedThem() {
        listOf(SettingsDestination.USAGE, SettingsDestination.USAGE_LOG).forEach { source ->
            val entry = listOf(source)
            assertEquals(entry, settingsNavigationPop(settingsNavigationPush(entry, SettingsDestination.USAGE_DETAIL)))
        }
    }

    @Test fun repeatedCurrentPageDoesNotCreateAnExtraBackStep() {
        val entry = listOf(SettingsDestination.MINIMAX_QUOTA)
        assertEquals(entry, settingsNavigationPush(entry, SettingsDestination.MINIMAX_QUOTA))
        assertNull(settingsNavigationPop(entry))
    }
}
