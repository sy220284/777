package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.harness.session.VersionedSessionStore
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Re-encode installed main-branch sessions before SQLite becomes the authoritative
 * runtime. Old files are never deleted: each Session EventLog imports atomically,
 * and snapshot adoption is idempotent across process death.
 */
internal class LocalLegacySqliteMigration(
    private val root: File,
    private val sessionsRoot: File,
    private val json: Json,
    private val snapshots: SqliteSessionSnapshotStore,
    private val events: SqliteSessionEventDatabase,
) {
    fun migrate() {
        if (events.legacyAdoptionFinished()) return
        // Move the single-file predecessor into the same historical folder first.
        // Startup has not initialized the foreground Session yet. An explicit legacy
        // file ID always wins; missing IDs receive a stable, isolated import owner.
        migrateLegacySessionFiles(root, sessionsRoot, "legacy-import")
        val legacySnapshots = VersionedSessionStore(sessionsRoot, json)
        val oldIds = legacySnapshots.ids().toMutableSet()
        val eventPattern = Regex("^(.+)\\.events\\.jsonl(?:\\.part-[0-9]+(?:\\.gz)?)?$")
        sessionsRoot.listFiles().orEmpty().forEach { file ->
            eventPattern.matchEntire(file.name)?.groupValues?.get(1)?.let(oldIds::add)
        }
        for (id in oldIds.sorted()) {
            val old = legacySnapshots.read(id)?.document
            val existing = snapshots.read(id)?.document
            if (old != null && existing != null && !events.legacyImportCommitted(id)) {
                check(existing.payload == old.payload) {
                    "旧会话 $id 与 SQLite 已有不同快照，禁止自动覆盖；原数据已保留"
                }
            }
            val file = File(sessionsRoot, "$id.events.jsonl")
            val oldLog = LocalSessionEventLog(file, json, sessionId = id)
            try {
                events.importLegacyEvents(id, oldLog)
            } finally {
                oldLog.close()
            }
            if (old != null && existing == null) {
                snapshots.write(id, old.payload, old.updatedAt)
            }
        }
        // Only after every historical snapshot and EventLog was committed.
        // Future Session deletion must not re-import the retained legacy files.
        events.finishLegacyAdoption()
    }
}
