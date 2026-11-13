package com.labteto.dshmobile.local.session

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The sole importer of the pre-session-folder persistence layout. Runtime Bootstrap
 * only invokes it; it must not own a second, unconditional file-copy migration.
 *
 * Claim the destination once, before either copy. If startup crashes between files,
 * a later startup must finish the same migration instead of copying old private
 * history into whichever new session happens to be selected next.
 */
internal fun migrateLegacySessionFiles(root: File, sessionsRoot: File, currentSessionId: String) {
    val legacy = File(root, "session.json")
    if (!legacy.isFile) return
    sessionsRoot.mkdirs()
    val lock = File(root, "session.legacy-migration.lock")
    FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        channel.lock().use {
            val ownerFile = File(root, "session.legacy-migration.owner")
            val owner = if (ownerFile.exists()) {
                ownerFile.readText().trim()
            } else {
                // An explicit old ID takes precedence over the *current* selected ID.
                // Otherwise a session switch could clone the same old Chat history.
                val recorded = runCatching {
                    Json.parseToJsonElement(legacy.readText()).jsonObject["id"]
                        ?.jsonPrimitive?.contentOrNull
                }.getOrElse { error ->
                    throw IllegalStateException("旧会话数据不可解码，禁止将其迁移到其他会话", error)
                }
                (recorded ?: currentSessionId).also { claim ->
                    validateLegacySessionOwner(claim)
                    writeMigrationFileAtomically(ownerFile, claim.toByteArray(Charsets.UTF_8))
                }
            }
            validateLegacySessionOwner(owner)
            val destination = File(sessionsRoot, "$owner.json")
            if (!destination.exists()) writeMigrationFileAtomically(destination, legacy.readBytes())
            val legacyEvents = File(root, "session.events.jsonl")
            val eventsDestination = File(sessionsRoot, "$owner.events.jsonl")
            if (legacyEvents.isFile && !eventsDestination.exists()) {
                writeMigrationFileAtomically(eventsDestination, legacyEvents.readBytes())
            }
        }
    }
}

private fun validateLegacySessionOwner(id: String) {
    require(id.matches(Regex("[A-Za-z0-9._-]{1,128}")) && id != "." && id != "..") {
        "旧会话迁移目标标识无效"
    }
}

/** Stage in the same directory and publish only after the whole file is copied. */
private fun writeMigrationFileAtomically(destination: File, bytes: ByteArray) {
    val temp = File.createTempFile(".legacy-import-", ".tmp", destination.parentFile)
    try {
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        // The migration lock serializes independent bootstrap processes. Never
        // overwrite an existing canonical snapshot or user-authored session.
        if (!destination.exists()) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    } finally {
        temp.delete()
    }
}
