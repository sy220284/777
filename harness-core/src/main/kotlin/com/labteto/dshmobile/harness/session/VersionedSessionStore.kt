package com.labteto.dshmobile.harness.session

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SessionDocument(
    val formatVersion: Int,
    val id: String,
    val updatedAt: Long,
    val payload: JsonObject,
)

data class SessionLoadResult(
    val document: SessionDocument,
    val migrated: Boolean,
    val legacy: Boolean,
    val recovered: Boolean = false,
)

class FutureSessionVersionException(version: Int, current: Int) :
    IllegalStateException("会话格式版本 $version 高于当前支持版本 $current，拒绝写入以避免破坏数据")

class SessionMigrationRegistry(
    val currentVersion: Int = CURRENT_FORMAT_VERSION,
) {
    private val migrations = linkedMapOf<Int, (JsonObject) -> JsonObject>()

    init {
        require(currentVersion >= 1) { "当前会话格式版本至少为 1" }
        register(0) { it }
    }

    @Synchronized
    fun register(fromVersion: Int, migration: (JsonObject) -> JsonObject) {
        require(fromVersion in 0 until currentVersion) { "迁移起始版本超出范围：$fromVersion" }
        migrations[fromVersion] = migration
    }

    fun migrate(fromVersion: Int, payload: JsonObject): JsonObject {
        if (fromVersion > currentVersion) throw FutureSessionVersionException(fromVersion, currentVersion)
        var version = fromVersion
        var current = payload
        while (version < currentVersion) {
            val migration = synchronized(this) { migrations[version] }
                ?: error("缺少会话迁移：$version -> ${version + 1}")
            current = migration(current)
            version += 1
        }
        return current
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

class VersionedSessionStore(
    private val root: File,
    private val json: Json,
    private val migrations: SessionMigrationRegistry = SessionMigrationRegistry(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        root.mkdirs()
    }

    fun read(id: String): SessionLoadResult? {
        val file = fileFor(id)
        if (!file.isFile) return null
        return try {
            readFromFile(id, file)
        } catch (future: FutureSessionVersionException) {
            throw future
        } catch (primaryError: Exception) {
            val backup = backupFor(id)
            if (!backup.isFile) throw primaryError
            val recovered = try {
                readFromFile(id, backup)
            } catch (future: FutureSessionVersionException) {
                throw future
            } catch (_: Exception) {
                throw primaryError
            }
            runCatching { file.copyTo(corruptFor(id), overwrite = true) }
            atomicWrite(file, backup.readText())
            recovered.copy(recovered = true)
        }
    }

    fun repair(id: String): SessionLoadResult? = read(id)

    fun write(id: String, payload: JsonObject, updatedAt: Long = clock()): SessionDocument {
        validateId(id)
        val source = fileFor(id)
        val existing = read(id)
        if (existing?.legacy == true) {
            val checkpoint = checkpointFor(id, 0)
            if (!checkpoint.exists()) source.copyTo(checkpoint, overwrite = false)
        }
        if (source.isFile) {
            source.copyTo(backupFor(id), overwrite = true)
        }
        val document = SessionDocument(
            formatVersion = migrations.currentVersion,
            id = id,
            updatedAt = updatedAt,
            payload = payload,
        )
        atomicWrite(source, json.encodeToString(SessionDocument.serializer(), document))
        val backup = backupFor(id)
        if (!backup.isFile) {
            source.copyTo(backup, overwrite = false)
        }
        return document
    }

    fun delete(id: String): Boolean {
        validateId(id)
        var changed = fileFor(id).delete()
        changed = backupFor(id).delete() || changed
        root.listFiles().orEmpty()
            .filter { file ->
                file.name.startsWith("$id.checkpoint-v") ||
                    file.name.startsWith("$id.corrupt-")
            }
            .forEach { file -> changed = file.delete() || changed }
        return changed
    }

    fun list(): List<SessionLoadResult> = root.listFiles().orEmpty()
        .filter { file ->
            file.isFile &&
                file.name.endsWith(SESSION_SUFFIX) &&
                !file.name.endsWith(TEMP_SUFFIX) &&
                !file.name.endsWith(BACKUP_SUFFIX) &&
                !file.name.contains(CHECKPOINT_MARKER) &&
                !file.name.contains(CORRUPT_MARKER)
        }
        .mapNotNull { file ->
            val id = file.name.removeSuffix(SESSION_SUFFIX)
            try {
                read(id)
            } catch (future: FutureSessionVersionException) {
                throw future
            } catch (_: Exception) {
                null
            }
        }
        .sortedByDescending { it.document.updatedAt }

    fun export(id: String): String {
        val loaded = read(id) ?: error("会话不存在：$id")
        return json.encodeToString(SessionDocument.serializer(), loaded.document)
    }

    fun import(serialized: String, overwrite: Boolean = false): SessionDocument {
        val document = json.decodeFromString(SessionDocument.serializer(), serialized)
        if (document.formatVersion > migrations.currentVersion) {
            throw FutureSessionVersionException(document.formatVersion, migrations.currentVersion)
        }
        val target = fileFor(document.id)
        require(overwrite || !target.exists()) { "会话已存在：${document.id}" }
        val payload = migrations.migrate(document.formatVersion, document.payload)
        return write(document.id, payload, document.updatedAt)
    }

    fun checkpoint(id: String, version: Int = migrations.currentVersion): File {
        val source = fileFor(id)
        require(source.isFile) { "会话不存在：$id" }
        val target = checkpointFor(id, version)
        source.copyTo(target, overwrite = true)
        return target
    }

    private fun readFromFile(id: String, file: File): SessionLoadResult {
        val element = json.parseToJsonElement(file.readText()).jsonObject
        val wrappedVersion = element["formatVersion"]?.jsonPrimitive?.intOrNull
        val legacy = wrappedVersion == null
        val version = wrappedVersion ?: 0
        if (version > migrations.currentVersion) {
            throw FutureSessionVersionException(version, migrations.currentVersion)
        }
        val payload = if (legacy) {
            element
        } else {
            element["payload"]?.jsonObject ?: error("会话文档缺少 payload：$id")
        }
        val migratedPayload = migrations.migrate(version, payload)
        val updatedAt = if (legacy) {
            file.lastModified().takeIf { it > 0L } ?: clock()
        } else {
            element["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: file.lastModified()
        }
        return SessionLoadResult(
            document = SessionDocument(
                formatVersion = migrations.currentVersion,
                id = id,
                updatedAt = updatedAt,
                payload = migratedPayload,
            ),
            migrated = version != migrations.currentVersion,
            legacy = legacy,
        )
    }

    private fun fileFor(id: String): File {
        validateId(id)
        return File(root, "$id$SESSION_SUFFIX")
    }

    private fun backupFor(id: String): File =
        File(root, "$id$BACKUP_SUFFIX")

    private fun checkpointFor(id: String, version: Int): File =
        File(root, "$id.checkpoint-v$version.json")

    private fun corruptFor(id: String): File =
        File(root, "$id.corrupt-${clock()}.json")

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "非法会话编号：$id" }
    }

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        const val SESSION_SUFFIX = ".json"
        const val TEMP_SUFFIX = ".json.tmp"
        const val BACKUP_SUFFIX = ".backup.json"
        const val CHECKPOINT_MARKER = ".checkpoint-v"
        const val CORRUPT_MARKER = ".corrupt-"
    }
}
