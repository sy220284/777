package com.labteto.dshmobile.local.chat

import kotlinx.serialization.Serializable

@Serializable
enum class CharacterLifeEventKind {
    BACKGROUND,
    ONGOING,
    MILESTONE,
}

@Serializable
data class CharacterLifeEvent(
    val id: String = "",
    val summary: String = "",
    val kind: CharacterLifeEventKind = CharacterLifeEventKind.BACKGROUND,
    val source: String = "persona",
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val expiresAt: Long = 0L,
    val active: Boolean = true,
)

@Serializable
data class CharacterLifeState(
    val lastAdvancedAt: Long = 0L,
    val dayIndex: Int = 0,
    val currentBeat: String = "",
    val activeEvents: List<CharacterLifeEvent> = emptyList(),
)
