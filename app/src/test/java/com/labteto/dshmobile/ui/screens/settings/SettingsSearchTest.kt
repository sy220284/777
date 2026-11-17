package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    private val entries = listOf(
        SettingsSearchEntry("model", SettingsDestination.MODELS, 1, listOf("API Key", "模型")),
        SettingsSearchEntry("appearance", SettingsDestination.APPEARANCE, 2, listOf("深色", "dark", "壁纸")),
    )
    private val titles = mapOf(1 to "模型", 2 to "外观")
    private fun search(query: String) = searchSettingsEntries(query, entries) { titles.getValue(it) }
    @Test fun fullWidthAndWhitespaceMatchApiKeyAlias() {
        assertEquals("model", search("ＡＰＩ　Key").single().id)
    }
    @Test fun commonWordsFindActualDestination() {
        assertEquals(SettingsDestination.APPEARANCE, search("深色").single().destination)
        assertEquals("appearance", search("DARK").single().id)
    }
    @Test fun titleMatchesRankAheadOfAliases() {
        val list = entries + SettingsSearchEntry("exact", SettingsDestination.ADVANCED, 3, emptyList())
        val hits = searchSettingsEntries("模型", list) { if (it == 3) "模型" else titles.getValue(it) }
        assertEquals(listOf("model", "exact"), hits.map { it.id })
    }
    @Test fun resultsAreNotSilentlyTruncated() {
        val list = (1..40).map { SettingsSearchEntry("item-$it", SettingsDestination.MODELS, 1, emptyList()) }
        assertEquals(40, searchSettingsEntries("模型", list) { "模型" }.size)
    }
    @Test fun blankQueryShowsBrowsableSettings() {
        assertEquals(entries, search(" "))
    }
    @Test fun everySettingsPageHasExactlyOneSearchableTitle() {
        val pages = SettingsDestination.entries.filterNot {
            it in setOf(SettingsDestination.ROOT, SettingsDestination.SEARCH, SettingsDestination.USAGE_DETAIL)
        }
        assertEquals(pages.toSet(), settingsPageEntries.map { it.destination }.toSet())
        assertEquals(settingsPageEntries.size, settingsPageEntries.map { it.destination }.distinct().size)
        assertEquals(settingsSearchEntries.size, settingsSearchEntries.map { it.id }.distinct().size)
    }
}
