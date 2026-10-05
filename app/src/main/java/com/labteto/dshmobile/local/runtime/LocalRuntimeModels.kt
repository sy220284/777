package com.labteto.dshmobile.local.runtime



/** State rendered by the standalone, on-device Harness screen. */
data class LocalHarnessResourceState(
    val activeModelRequests: Int = 0,
    val activeAgents: Int = 0,
    val activeTerminals: Int = 0,
    val activeVirtualDisplays: Int = 0,
    val activeLanguageServers: Int = 0,
    val maxModelRequests: Int = 1,
    val maxAgents: Int = 1,
    val maxTerminals: Int = 1,
    val maxVirtualDisplays: Int = 1,
    val maxLanguageServers: Int = 1,
    val resourcePressure: String = "low",
)
