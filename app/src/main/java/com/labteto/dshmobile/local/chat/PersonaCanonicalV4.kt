package com.labteto.dshmobile.local.chat

/**
 * The current persona contract is authoritative.
 *
 * No legacy character-text recovery or mapping of retired fact categories.
 * Old data is never rewritten into a new, semantically different field.
 * Runtime/story boundaries remain outside the fixed character fact collection.
 */
internal fun PersonaProfile.canonicalV4(): PersonaProfile = copy(
    facts = facts.filter { CharacterFactCategories.isSupported(it.category.trim()) },
)
