package com.labteto.dshmobile.local.session

/**
 * Narrow Feature hook for interpreting domain fields after a durable Session document is decoded.
 *
 * Session infrastructure owns file/version/transaction semantics. Each product Feature owns the
 * compatibility rules for its own persisted fields and contributes them through the app graph.
 */
internal interface LocalSessionDomainCodec {
    val id: String

    fun normalizeLoaded(session: LocalHarnessSession): LocalHarnessSession
}
