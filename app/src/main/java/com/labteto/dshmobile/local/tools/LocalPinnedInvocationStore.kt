package com.labteto.dshmobile.local.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's explicit, persistent chat-composer choices. No remote storage or model-history text. */
internal data class LocalPinnedInvocation(val kind: Kind, val id: String) {
    enum class Kind { CONNECTOR, SKILL }

    init {
        val pattern = when (kind) {
            Kind.CONNECTOR -> Regex("[A-Za-z0-9_-]{1,48}")
            Kind.SKILL -> Regex("[a-z][a-z0-9-]{1,47}")
        }
        require(pattern.matches(id)) { "无效的连接器或技能标识" }
    }

    fun encode(): String = "${kind.name.lowercase()}:$id"

    companion object {
        fun decode(raw: String): LocalPinnedInvocation? {
            val kind = when (raw.substringBefore(':')) {
                "connector" -> Kind.CONNECTOR
                "skill" -> Kind.SKILL
                else -> return null
            }
            return runCatching { LocalPinnedInvocation(kind, raw.substringAfter(':', "")) }.getOrNull()
        }
    }
}

/**
 * Keeps selected tools visible across messages, app restarts, and new sessions until the
 * user removes them. Lives under noBackupFilesDir so selections remain local to this device.
 * Selection does not grant a tool permission or imply a disconnected server is available.
 */
@Singleton
internal class LocalPinnedInvocationStore @Inject constructor(@ApplicationContext context: Context) {
    private val lock = Any()
    private val directory = File(context.noBackupFilesDir, "local-harness")
    private val file = File(directory, "composer-invocations-v1.txt")
    private val mutable = MutableStateFlow(read())
    val selected: StateFlow<List<LocalPinnedInvocation>> = mutable.asStateFlow()

    fun toggle(item: LocalPinnedInvocation): List<LocalPinnedInvocation> = synchronized(lock) {
        val previous = mutable.value
        val next = if (item in previous) previous - item else {
            require(previous.size < 12) { "最多可以同时固定 12 个连接器和技能" }
            previous + item
        }
        save(next)
        mutable.value = next
        next
    }

    fun remove(item: LocalPinnedInvocation): List<LocalPinnedInvocation> = synchronized(lock) {
        val next = mutable.value - item
        if (next != mutable.value) {
            save(next)
            mutable.value = next
        }
        next
    }

    private fun read(): List<LocalPinnedInvocation> =
        runCatching {
            if (!file.isFile) emptyList() else file.readLines(Charsets.UTF_8)
                .filter(String::isNotBlank).mapNotNull(LocalPinnedInvocation::decode)
                .distinct().take(12)
        }.getOrDefault(emptyList())

    private fun save(value: List<LocalPinnedInvocation>) {
        require(directory.isDirectory || directory.mkdirs()) { "无法保存固定调用选择" }
        val temp = File.createTempFile("composer-", ".tmp", directory)
        try {
            temp.writeText(value.joinToString("\n", postfix = "\n") { it.encode() }, Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}

/** A live entry in the composer picker; disconnected pins remain visible for removal. */
internal data class LocalComposerInvocationOption(
    val invocation: LocalPinnedInvocation,
    val label: String,
    val detail: String,
    val available: Boolean = true,
)
