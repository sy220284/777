package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Disposable derived state; the original EventLog remains the only business authority. */
@Serializable
internal data class LocalSessionProjectionCheckpoint(
    val version: Int,
    val identity: String,
    val throughSequence: Long,
    val payload: String,
)

internal class LocalSessionProjectionCheckpoints(private val logFile: File, private val json: Json) {
    fun read(name: String): LocalSessionProjectionCheckpoint? {
        val file = fileFor(name)
        if (!file.isFile || file.length() > MAX_CHECKPOINT_BYTES) return null
        return try {
            json.decodeFromString<LocalSessionProjectionCheckpoint>(file.readText())
        } catch (error: Exception) {
            AppLog.warn("SessionProjection", "忽略损坏的派生检查点：${file.name}", error)
            null
        }
    }

    fun write(name: String, checkpoint: LocalSessionProjectionCheckpoint) {
        val previous = read(name)
        if (previous?.identity == checkpoint.identity && previous.version == checkpoint.version &&
            previous.throughSequence > checkpoint.throughSequence) return
        val payload = json.encodeToString(LocalSessionProjectionCheckpoint.serializer(), checkpoint).toByteArray()
        require(payload.size <= MAX_CHECKPOINT_BYTES) { "会话投影检查点超过预算" }
        val target = fileFor(name)
        val temporary = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(payload); output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    fun clear() {
        logFile.parentFile?.listFiles().orEmpty()
            .filter { it.name.startsWith(logFile.name + ".projection-") }
            .forEach { it.delete() }
    }

    private fun fileFor(name: String): File {
        require(name.matches(Regex("[a-z0-9.-]{1,64}"))) { "会话投影检查点名称无效" }
        return File(logFile.parentFile, logFile.name + ".projection-" + name + ".json")
    }

    private companion object { const val MAX_CHECKPOINT_BYTES = 8 * 1024 * 1024 }
}
