package com.labteto.dshmobile.local.memory

import kotlinx.serialization.Serializable

@Serializable
internal data class MemoryDocument(
    val formatVersion: Int = 2,
    val records: List<MemoryRecord> = emptyList(),
)
