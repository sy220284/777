package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.SessionEventLog
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

data class LocalSessionStorageStatus(
    val totalBytes: Long,
    val freeBytes: Long,
    val budgetBytes: Long,
    val sessionCount: Int,
    val rawSegmentCount: Int,
    val compressedSegmentCount: Int,
) {
    val overBudget: Boolean get() = totalBytes >= budgetBytes
    val lowFreeSpace: Boolean get() = freeBytes < LOW_FREE_SPACE_BYTES
    val warning: Boolean get() = overBudget || lowFreeSpace

    companion object {
        const val DEFAULT_BUDGET_BYTES = 256L * 1024L * 1024L
        const val LOW_FREE_SPACE_BYTES = 512L * 1024L * 1024L
    }
}

/**
 * User-facing governance for append-only local Session Event storage.
 *
 * Raw facts are never deleted here. The manager can compress immutable legacy segments and export
 * the complete local session directory. Reclaiming retained history remains an explicit user
 * action through session deletion after export.
 */
internal class LocalSessionStorageManager(
    private val root: File,
    private val json: Json
) {
    fun status(): LocalSessionStorageStatus {
        val files = durableFiles()
        val rawSegments = files.count { isRawSegment(it.name) }
        val compressedSegments = files.count { isCompressedSegment(it.name) }
        val sessionIds = files.mapNotNull(::sessionIdOf).toSet()
        return LocalSessionStorageStatus(
            totalBytes = files.sumOf { it.length() },
            freeBytes = root.usableSpace,
            budgetBytes = LocalSessionStorageStatus.DEFAULT_BUDGET_BYTES,
            sessionCount = sessionIds.size,
            rawSegmentCount = rawSegments,
            compressedSegmentCount = compressedSegments,
        )
    }

    /**
     * Compress every legacy rotated segment, including immutable segments belonging to the active
     * session. SessionEventLog serializes each path through a shared path lock, so this remains safe
     * while an append is in flight.
     */
    suspend fun compactAll(): LocalSessionStorageStatus {
        val ids = durableFiles().mapNotNull(::sessionIdOf).distinct()
        for (id in ids) {
            val log = SessionEventLog(File(root, "$id.events.jsonl"), json)
            while (log.archiveLegacySegments(limit = 16) > 0) {
                delay(COMPACTION_YIELD_MILLIS)
            }
        }
        return status()
    }

    /**
     * Export a lossless snapshot of all durable session files. Each source file is copied directly
     * into the ZIP; the source directory is never modified.
     */
    fun exportAll(output: OutputStream): Long {
        var exportedBytes = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            durableFiles().sortedBy(File::name).forEach { source ->
                val entry = ZipEntry(source.name).apply {
                    time = source.lastModified()
                }
                zip.putNextEntry(entry)
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        exportedBytes += read
                    }
                }
                zip.closeEntry()
            }
        }
        return exportedBytes
    }

    private fun durableFiles(): List<File> =
        root.listFiles().orEmpty()
            .filter { file ->
                file.isFile &&
                    !file.name.endsWith(".tmp") &&
                    !file.name.contains(".corrupt-") &&
                    (
                        file.name.endsWith(".json") ||
                            file.name.contains(".events.jsonl")
                    )
            }

    private fun sessionIdOf(file: File): String? {
        val name = file.name
        return when {
            ".events.jsonl" in name -> name.substringBefore(".events.jsonl").takeIf(String::isNotBlank)
            name.endsWith(".json") -> name.removeSuffix(".json").takeIf(String::isNotBlank)
            else -> null
        }
    }

    private fun isRawSegment(name: String): Boolean =
        RAW_SEGMENT.matches(name)

    private fun isCompressedSegment(name: String): Boolean =
        COMPRESSED_SEGMENT.matches(name)

    private companion object {
        val RAW_SEGMENT = Regex("^.+\\.events\\.jsonl\\.part-[0-9]+$")
        val COMPRESSED_SEGMENT = Regex("^.+\\.events\\.jsonl\\.part-[0-9]+\\.gz$")
        const val COMPACTION_YIELD_MILLIS = 25L
        const val COPY_BUFFER_BYTES = 32 * 1024
    }
}
