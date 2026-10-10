package com.labteto.dshmobile.harness.session

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Durable registry of actual session identities.
 *
 * Ordinary enumeration never infers sessions from directory filenames. All VersionedSessionStore
 * instances in one process share one catalog state and mutation lock per directory. The one-time
 * migration/recovery path examines document CONTENT, including valid backups, to adopt old sessions.
 * Event logs, projection checkpoints and other JSON files have no session identity and are ignored.
 */
internal class DurableSessionCatalog(
    private val root: File,
    private val json: Json,
) {
    private val shared = states.computeIfAbsent(root.canonicalPath) { SharedState() }
    private val primary = File(root, CATALOG_NAME)
    private val backup = File(root, BACKUP_NAME)

    fun ids(): List<String> = synchronized(shared) {
        ensureLoaded()
        shared.ids.toList()
    }

    /** Register BEFORE writing a snapshot: a process crash cannot orphan a committed snapshot. */
    fun register(id: String) = synchronized(shared) {
        require(SESSION_ID_PATTERN.matches(id)) { "非法会话编号：$id" }
        ensureLoaded()
        if (id in shared.ids) return@synchronized
        val next = LinkedHashSet(shared.ids).apply { add(id) }
        persist(next)
        shared.ids = next
    }

    /**
     * Adopt a known legacy file copied after initial migration, but only when its contents
     * genuinely identify a session. A projection file must never register itself by being read.
     */
    fun adopt(id: String) {
        require(SESSION_ID_PATTERN.matches(id)) { "非法会话编号：$id" }
        // Normal reads should not decode a second copy of the same large snapshot.
        val alreadyRegistered = synchronized(shared) {
            ensureLoaded()
            id in shared.ids
        }
        if (alreadyRegistered) return
        val primarySnapshot = File(root, "$id.json")
        val backupSnapshot = File(root, "$id.backup.json")
        if (documentHasIdentity(primarySnapshot, id) || documentHasIdentity(backupSnapshot, id)) {
            register(id)
        }
    }

    /** Unregister only AFTER primary/backup deletion; interrupted deletion never hides live data. */
    fun unregister(id: String) = synchronized(shared) {
        ensureLoaded()
        if (id !in shared.ids) return@synchronized
        val next = LinkedHashSet(shared.ids).apply { remove(id) }
        persist(next)
        shared.ids = next
    }

    private fun ensureLoaded() {
        if (shared.initialized) return
        val fromPrimary = read(primary)
        val fromBackup = if (fromPrimary == null) read(backup) else null
        val loaded = when {
            fromPrimary != null -> fromPrimary
            else -> {
                // Migration or disaster recovery ONLY. Check document identities, not suffixes.
                // Also reconcile snapshots committed after a damaged catalog's last good backup.
                val recovered = linkedSetOf<String>()
                if (fromBackup != null) recovered.addAll(fromBackup)
                recovered.addAll(discoverExistingDocuments())
                recovered
            }
        }
        if (fromPrimary == null) persist(loaded)
        shared.ids = LinkedHashSet(loaded)
        shared.initialized = true
    }

    private fun read(file: File): LinkedHashSet<String>? {
        if (!file.isFile) return null
        val payload = runCatching {
            json.decodeFromString<CatalogDocument>(file.readText())
        }.getOrNull() ?: return null
        if (payload.schema != CATALOG_SCHEMA ||
            payload.sessionIds.any { !SESSION_ID_PATTERN.matches(it) } ||
            payload.sessionIds.size != payload.sessionIds.toSet().size) return null
        return LinkedHashSet(payload.sessionIds)
    }

    /** Invoked only when the registry is absent or damaged (once per process/directory). */
    private fun discoverExistingDocuments(): Set<String> {
        val discovered = linkedSetOf<String>()
        root.listFiles().orEmpty().asSequence()
            .filter(File::isFile)
            .forEach { file ->
                // ".backup" is a legal part of a session ID, too. A backup-shaped filename
                // must not conceal a real session named "foo.backup": prefer document identity.
                candidateIdentities(file.name)
                    .firstOrNull { candidate -> documentHasIdentity(file, candidate) }
                    ?.let(discovered::add)
            }
        return discovered
    }

    private fun candidateIdentities(name: String): List<String> {
        // Path reconstruction is limited to ONE-TIME migration, never ordinary enumeration.
        // For unwrapped documents lacking an ID, the backup interpretation is preferred.
        val candidates = buildList {
            if (name.endsWith(BACKUP_SUFFIX)) add(name.removeSuffix(BACKUP_SUFFIX))
            if (name.endsWith(SESSION_SUFFIX)) add(name.removeSuffix(SESSION_SUFFIX))
        }
        return candidates.distinct().filter(SESSION_ID_PATTERN::matches)
    }

    private fun documentHasIdentity(file: File, id: String): Boolean {
        if (!file.isFile) return false
        val rootObject = runCatching { json.parseToJsonElement(file.readText()).jsonObject }
            .getOrNull() ?: return false
        val wrappedVersion = (rootObject["formatVersion"] as? JsonPrimitive)?.intOrNull
        if ("formatVersion" in rootObject) {
            // Valid future-version sessions remain discoverable, so the normal reader can report
            // FutureSessionVersionException instead of silently hiding them.
            return wrappedVersion != null &&
                (rootObject["id"] as? JsonPrimitive)?.content == id &&
                rootObject["payload"] is JsonObject
        }
        // Old unwrapped snapshots are self-describing through their session ID, or through
        // established legacy session fields for snapshots saved before IDs became mandatory.
        val embeddedId = (rootObject["id"] as? JsonPrimitive)?.content
        if (embeddedId != null && embeddedId != id) return false
        return (embeddedId == id && rootObject.keys.any { it in LEGACY_FIELDS }) ||
            (embeddedId == null &&
                "title" in rootObject &&
                rootObject.keys.any { it in LEGACY_FIELDS_WITHOUT_ID })
    }

    private fun persist(ids: Set<String>) {
        val bytes = json.encodeToString(
            CatalogDocument.serializer(), CatalogDocument(CATALOG_SCHEMA, ids.toList()),
        ).toByteArray(Charsets.UTF_8)
        val temporary = File(root, "$CATALOG_NAME.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (primary.isFile) {
                // Backup is only a recovery aid; the primary is updated atomically below.
                Files.copy(primary.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(temporary.toPath(), primary.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), primary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private class SharedState {
        var initialized = false
        var ids = linkedSetOf<String>()
    }

    private companion object {
        // Deliberately NOT .json: this metadata is not a session snapshot or exportable event log.
        const val CATALOG_NAME = ".session-catalog.v1"
        const val BACKUP_NAME = ".session-catalog.backup.v1"
        const val CATALOG_SCHEMA = 1
        const val SESSION_SUFFIX = ".json"
        const val BACKUP_SUFFIX = ".backup.json"
        val SESSION_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        val LEGACY_FIELDS = setOf(
            "title", "updatedAt", "usageMode", "chatState", "messages", "modelHistory",
            "personaId", "transcriptWindow", "plan", "todos", "controlProjectedThroughSequence",
        )
        val LEGACY_FIELDS_WITHOUT_ID = LEGACY_FIELDS - "title"
        val states = ConcurrentHashMap<String, SharedState>()
    }
}

@Serializable
private data class CatalogDocument(val schema: Int, val sessionIds: List<String>)
