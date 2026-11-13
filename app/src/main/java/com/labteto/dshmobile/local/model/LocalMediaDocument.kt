package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.persistence.RecoveringDocumentFile
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Media owns the schema; shared persistence owns atomic commits, backup and quarantine. */
internal class LocalMediaDocument<T>(
    file: File,
    private val serializer: KSerializer<T>,
    private val json: Json,
    private val legacySource: () -> String? = { null },
) {
    private val durable = RecoveringDocumentFile(file)
    private var readFailure: Throwable? = null
    private val mutableRecoveryError = MutableStateFlow<String?>(null)
    val recoveryErrors: StateFlow<String?> = mutableRecoveryError
    val recoveryError: String? get() = mutableRecoveryError.value
    private fun failed(failure: Throwable) {
        readFailure = failure
        mutableRecoveryError.value = "媒体记录暂时无法恢复，原数据已保留。请恢复数据后再生成，避免重复付费。"
    }

    fun readOrNull(defaultValue: () -> T): T? = try {
        durable.read(defaultValue = {
            // Decode before the first commit. A damaged legacy record stays untouched.
            legacySource()?.let { raw ->
                val migrated = decode(raw)
                durable.restoreFromRecoverySource(raw, ::valid)
                migrated
            } ?: defaultValue()
        }, decode = ::decode).also { readFailure = null; mutableRecoveryError.value = null }
    } catch (failure: Exception) {
        failed(failure)
        AppLog.failure("MediaStorage", "restore_media_document", failure)
        null
    }

    fun requireAvailable() {
        readFailure?.let { throw IllegalStateException(recoveryError, it) }
    }

    fun write(value: T) {
        requireAvailable()
        try {
            durable.write(json.encodeToString(serializer, value), ::valid)
        } catch (failure: Exception) {
            failed(failure)
            AppLog.failure("MediaStorage", "commit_media_document", failure)
            throw failure
        }
    }

    private fun decode(raw: String): T = json.decodeFromString(serializer, raw)
    private fun valid(raw: String): Boolean = runCatching { decode(raw) }.isSuccess
}
