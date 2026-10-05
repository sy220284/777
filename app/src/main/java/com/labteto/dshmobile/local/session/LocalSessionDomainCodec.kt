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

    fun projectSummary(
        session: LocalHarnessSession,
        summary: LocalSessionSummary,
    ): LocalSessionSummary = summary
}

internal fun normalizeLocalSessionDomains(
    session: LocalHarnessSession,
    domainCodecs: List<LocalSessionDomainCodec>,
): LocalHarnessSession =
    domainCodecs.fold(session) { current, codec -> codec.normalizeLoaded(current) }

internal fun projectLocalSessionSummary(
    session: LocalHarnessSession,
    domainCodecs: List<LocalSessionDomainCodec>,
): LocalSessionSummary {
    val normalized = normalizeLocalSessionDomains(session, domainCodecs)
    val base = LocalSessionSummary(
        id = normalized.id,
        title = normalized.title,
        updatedAt = normalized.updatedAt,
        usageMode = normalized.usageMode,
        blank = normalized.transcriptIndex.totalMessageCount == 0L &&
            normalized.transcriptWindow.none { it.content.isNotBlank() } &&
            normalized.messages.none { it.content.isNotBlank() },
        summaryPreview = normalized.transcriptIndex.latestUserContent
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 72) it.take(72) + "…" else it },
        projectId = normalized.projectId,
        lineageId = normalized.lineageId.ifBlank { normalized.id },
    )
    return domainCodecs.fold(base) { current, codec ->
        codec.projectSummary(normalized, current)
    }
}
