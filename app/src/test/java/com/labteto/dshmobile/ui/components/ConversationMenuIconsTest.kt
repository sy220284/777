package com.labteto.dshmobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationMenuIconsTest {
    @Test
    fun menuUsesNewPaperInkVectorsWithConsistentViewport() {
        val icons = linkedMapOf(
            "WorkEvidence" to ConversationMenuIcons.WorkEvidence,
            "InteractiveDocument" to ConversationMenuIcons.InteractiveDocument,
            "Rename" to ConversationMenuIcons.Rename,
            "Pin" to ConversationMenuIcons.Pin,
            "Delete" to ConversationMenuIcons.Delete,
        )
        assertEquals(5, icons.size)
        assertEquals("SyEntry.Tasks", icons.getValue("WorkEvidence").name)
        icons.filterKeys { it != "WorkEvidence" }.forEach { (name, icon) ->
            assertEquals("ConversationMenu.$name", icon.name)
        }
        icons.values.forEach { icon ->
            assertEquals(24f, icon.viewportWidth, 0.01f)
            assertEquals(24f, icon.viewportHeight, 0.01f)
        }
    }
}
