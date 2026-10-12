package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDrawerPersonaSearchTest {
    private val people = listOf(
        PersonaGalleryEntry("a", PersonaProfile(name = "沈昀", coreIdentity = "工程师")),
        PersonaGalleryEntry("b", PersonaProfile(name = "叶梨", coreIdentity = "作家")),
    )

    @Test fun searchesOnlyPublicCharacterCardFields() {
        assertEquals(listOf("a"), localDrawerMatchingPersonas(people, "沈").map { it.id })
        assertEquals(listOf("a"), localDrawerMatchingPersonas(people, "工程").map { it.id })
        assertEquals(listOf("b"), localDrawerMatchingPersonas(people, "作家").map { it.id })
        assertTrue(localDrawerMatchingPersonas(people, "无此人").isEmpty())
        assertTrue(localDrawerMatchingPersonas(people, "  ").isEmpty())
    }
}
