package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Model-owned commit/recovery protocol; only confirmed durable metadata decides recovery. */
internal class LocalModelConfigurationTransaction(
    private val json: Json,
    private val readJournal: suspend () -> String?,
    private val writeJournal: suspend (String) -> Unit,
    private val clearJournal: suspend () -> Unit,
    private val committedToken: () -> String?,
    private val restore: suspend (JsonObject) -> Unit,
    private val cleanupFailed: (Exception) -> Unit,
) {
    private val unconfirmed = mutableSetOf<String>()

    suspend fun recover() {
        val raw = readJournal() ?: return
        val journal = json.parseToJsonElement(raw).jsonObject
        require(journal["version"]?.jsonPrimitive?.intOrNull == 1) { "模型配置恢复日志版本无效" }
        val token = requireNotNull(journal["transaction"]?.jsonPrimitive?.contentOrNull)
        if (token in unconfirmed || committedToken() != token) restore(journal)
        clearJournal()
        unconfirmed.remove(token)
    }

    suspend fun commit(journal: JsonObject, persist: suspend () -> Unit, activate: () -> Unit) {
        recover()
        val token = requireNotNull(journal["transaction"]?.jsonPrimitive?.contentOrNull)
        writeJournal(journal.toString())
        unconfirmed.add(token)
        try {
            persist()
            unconfirmed.remove(token)
        } catch (failure: Exception) {
            try {
                restore(journal)
                clearJournal()
                unconfirmed.remove(token)
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
        activate()
        // The transaction already committed. Cleanup is recoverable and must not turn a
        // durable success into a false failure or roll back a confirmed configuration.
        try { clearJournal() } catch (failure: Exception) { cleanupFailed(failure) }
    }
}
