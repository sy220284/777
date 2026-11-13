package com.labteto.dshmobile.persistence

internal enum class RecoveringJsonSource {
    MISSING,
    PRIMARY,
    BACKUP,
}

internal data class RecoveringJsonValue<T>(
    val value: T,
    val raw: String?,
    val source: RecoveringJsonSource,
)

internal class CorruptPersistedJsonException(
    label: String,
    cause: Throwable?,
) : IllegalStateException("持久化 JSON 损坏且没有可用备份：$label", cause)

/**
 * Decodes a JSON-backed preference without conflating corruption with a legitimate empty value.
 *
 * Missing primary+backup is a valid empty state. A corrupt primary may recover from the last known
 * good backup. If neither payload can be decoded, callers must fail the mutation instead of
 * overwriting durable data from a fabricated empty collection.
 */
internal fun <T> recoverJsonValue(
    primary: String?,
    backup: String?,
    label: String,
    emptyValue: () -> T,
    decode: (String) -> T,
): RecoveringJsonValue<T> {
    if (primary == null && backup == null) {
        return RecoveringJsonValue(
            value = emptyValue(),
            raw = null,
            source = RecoveringJsonSource.MISSING,
        )
    }

    var primaryFailure: Throwable? = null
    if (primary != null) {
        try {
            return RecoveringJsonValue(
                value = decode(primary),
                raw = primary,
                source = RecoveringJsonSource.PRIMARY,
            )
        } catch (failure: Exception) {
            primaryFailure = failure
        }
    }

    if (backup != null) {
        try {
            return RecoveringJsonValue(
                value = decode(backup),
                raw = backup,
                source = RecoveringJsonSource.BACKUP,
            )
        } catch (backupFailure: Exception) {
            primaryFailure?.let(backupFailure::addSuppressed)
            throw CorruptPersistedJsonException(label, backupFailure)
        }
    }

    throw CorruptPersistedJsonException(label, primaryFailure)
}
