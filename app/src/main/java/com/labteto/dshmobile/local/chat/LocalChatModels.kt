package com.labteto.dshmobile.local.chat



data class ChatPersonaCorrectionNotice(
    val id: Long,
    val personaId: String,
    val correction: String,
)
