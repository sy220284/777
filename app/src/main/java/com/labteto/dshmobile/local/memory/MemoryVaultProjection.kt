package com.labteto.dshmobile.local.memory

/** Read-only hierarchy over MemoryStore facts; no files or parallel memory database. */
internal data class MemoryVaultFolder(
    val scope: MemoryScope,
    val ownerId: String?,
    val kind: MemoryKind,
    val records: List<MemoryRecord>,
) {
    val key: String get() = "${scope.name}|${ownerId.orEmpty()}|${kind.name}"
}

internal fun memoryVaultFolders(records: List<MemoryRecord>): List<MemoryVaultFolder> =
    records.asSequence()
        .filter(MemoryRecord::active)
        .groupBy { Triple(it.scope, when (it.scope) {
            MemoryScope.GLOBAL -> null
            MemoryScope.PROJECT -> it.projectId
            MemoryScope.LINEAGE -> it.lineageId
        }, it.kind) }
        .map { (key, contents) ->
            MemoryVaultFolder(
                scope = key.first,
                ownerId = key.second,
                kind = key.third,
                records = contents.sortedWith(
                    compareByDescending<MemoryRecord> { it.pinned }
                        .thenByDescending { it.updatedAt }
                        .thenBy(MemoryRecord::id),
                ),
            )
        }
        .sortedWith(
            compareBy<MemoryVaultFolder> { it.scope.ordinal }
                .thenBy { it.ownerId.orEmpty() }
                .thenBy { it.kind.ordinal },
        )
