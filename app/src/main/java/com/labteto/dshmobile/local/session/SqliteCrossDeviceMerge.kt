package com.labteto.dshmobile.local.session

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.labteto.dshmobile.local.model.LocalMediaDatabase
import com.labteto.dshmobile.local.model.LocalMediaStudioBackupSnapshot
import com.labteto.dshmobile.local.persistence.DurableFileCommit
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Crash-replayable, additive Session + Media Studio merger.
 * Never replace the running databases. A complete pair of merged images is prepared
 * and fsync'd before the first destination is installed at next process startup.
 */
internal class SqliteCrossDeviceMerge(
    private val context: Context, private val json: Json,
) {
    @Serializable private data class Plan(
        val sourceSnapshot: String, val sourceEvents: String, val sourceStudio: String?,
        val beforeSnapshot: String, val beforeEvents: String, val beforeStudio: String?,
        val afterSnapshot: String, val afterEvents: String, val afterStudio: String?,
        val studioNames: Map<String,String>, val importedCount: Int,
    )

    private val snapshotTables = listOf(
        "session_snapshots", "session_summaries", "session_message_tail",
    )
    private val eventTables = listOf(
        "session_event_cursors", "session_events", "session_projection_checkpoints",
        "local_assets", "local_asset_locations", "local_asset_references",
        "local_asset_pending_references", "session_search_blocks",
        "session_search_coverage", "session_legacy_imports",
    )
    // Device-scoped migration cursors must stay local; backfill resets below.
    private val eventSingletons = setOf(
        "session_request_migration", "local_asset_backfill_progress", "session_legacy_adoption",
        "android_metadata",
    )
    private fun digest(file: File): String {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return "absent"
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "合并目标文件类型不安全：${file.path}"
        }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer,0,count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun copyLive(from: File, to: File) {
        require(from.isFile && !to.exists()) { "本地数据库不存在或暂存目标重复" }
        SQLiteDatabase.openDatabase(from.path,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("VACUUM INTO ?",arrayOf(to.absolutePath))
        }
        check(to.isFile && to.length() >= 100L) { "本机数据库快照不完整" }
    }
    private fun ids(file: File, table: String, column: String): Set<String> =
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT $column FROM $table",null).use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
            }
        }
    private fun newId(domain: String, source: String, id: String) =
        UUID.nameUUIDFromBytes("777-import:$domain:$source:$id".toByteArray()).toString()
    /** Change reference fields only. Literal dialogue, tool output, prompts and
     * other user-authored text remain byte-for-byte equal to the imported source.
     */
    private fun rewriteJson(value: String, sessions: Map<String,String>,
        media: Map<String,String>, files: Map<String,String>): String {
        if (sessions.isEmpty() && media.isEmpty() && files.isEmpty()) return value
        val identityKeys = setOf(
            "id", "sessionId", "session_id", "sourceSessionId", "targetSessionId",
            "parentSessionId", "parent_session_id", "lineageId", "lineage_id",
        )
        fun rewrite(node: JsonElement): JsonElement = when(node) {
            is JsonObject -> JsonObject(node.mapValues { (key, item) ->
                if (item is JsonPrimitive && item.isString) {
                    val next = when {
                        key == "fileName" -> files[item.content]
                        key == "id" -> media[item.content] ?: sessions[item.content]
                        key in identityKeys -> sessions[item.content]
                        else -> null
                    }
                    if (next != null) JsonPrimitive(next) else item
                } else rewrite(item)
            })
            is JsonArray -> JsonArray(node.map(::rewrite))
            is JsonPrimitive -> node
        }
        return json.encodeToString(JsonElement.serializer(),rewrite(json.parseToJsonElement(value)))
    }
    private fun tables(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",null)
            .use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }

    /** Each source table is either imported in full or is explicitly device-global. */
    private fun mergeDatabase(
        destination: File, imported: File, order: List<String>, global: Set<String>,
        sessions: Map<String,String>, media: Map<String,String> = emptyMap(),
        fileNames: Map<String,String> = emptyMap(),
    ) {
        SQLiteDatabase.openDatabase(destination.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            SQLiteDatabase.openDatabase(imported.path,null,SQLiteDatabase.OPEN_READONLY).use { src ->
                val sourceTables = tables(src)
                require((sourceTables - (order.toSet() + global)).isEmpty() &&
                    sourceTables.all { it in tables(db) }) {
                    "备份数据表不兼容或包含未受支持的记录：${sourceTables - (order.toSet() + global)}"
                }
                // Identity maps are scoped by domain and field to preserve user text.
                db.beginTransaction()
                try {
                    order.filter(sourceTables::contains).forEach { table ->
                        src.rawQuery("SELECT * FROM $table",null).use { rows ->
                            while (rows.moveToNext()) {
                                val values = ContentValues()
                                rows.columnNames.forEachIndexed { i,col ->
                                    when(rows.getType(i)) {
                                        Cursor.FIELD_TYPE_NULL -> values.putNull(col)
                                        Cursor.FIELD_TYPE_INTEGER -> values.put(col,rows.getLong(i))
                                        Cursor.FIELD_TYPE_FLOAT -> values.put(col,rows.getDouble(i))
                                        Cursor.FIELD_TYPE_BLOB -> values.put(col,rows.getBlob(i))
                                        else -> {
                                            val text = rows.getString(i)
                                            val changed = when {
                                                col == "payload" -> rewriteJson(text,sessions,media,fileNames)
                                                col == "id" && table in setOf("session_snapshots","session_summaries") ->
                                                    sessions[text] ?: text
                                                col == "id" && table=="media" ->
                                                    media[text] ?: text
                                                col=="session_id" || col=="lineage_id" ->
                                                    sessions[text] ?: text
                                                else -> text
                                            }
                                            values.put(col,changed)
                                        }
                                    }
                                }
                                val inserted = db.insertWithOnConflict(
                                    table,null,values,SQLiteDatabase.CONFLICT_IGNORE)
                                if (inserted < 0L) {
                                    val primary = when(table) {
                                        "local_assets" -> "asset_id"
                                        "local_asset_locations" -> "relative_path"
                                        "metadata" -> "id"
                                        else -> error("合并主键重复：$table")
                                    }
                                    db.rawQuery("SELECT * FROM $table WHERE $primary=?",
                                        arrayOf(values.getAsString(primary))).use { found ->
                                        check(found.moveToFirst()) { "合并业务记录的唯一键冲突：$table" }
                                        found.columnNames.forEach { col ->
                                            if (table=="local_assets" && col=="created_at") return@forEach
                                            check(found.getString(found.getColumnIndexOrThrow(col)) ==
                                                values.getAsString(col)) {
                                                "本机已存在不同内容的相同资源：$table/$primary"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (order.contains("session_event_cursors") &&
                        tables(db).contains("local_asset_backfill_progress")) {
                        db.execSQL("UPDATE local_asset_backfill_progress SET last_session='',last_sequence=-1 WHERE id=1")
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                db.rawQuery("PRAGMA quick_check",null).use {
                    check(it.moveToFirst() && it.getString(0)=="ok") { "合并后的数据库完整性检查失败" }
                }
                db.rawQuery("PRAGMA foreign_key_check",null).use {
                    check(!it.moveToFirst()) { "合并后的数据引用不完整" }
                }
            }
        }
    }
    private fun studioFileNames(sourceDir: File): Map<String,String> {
        val source = LocalMediaStudioBackupSnapshot(context,json).validate(sourceDir)
        val target = File(context.filesDir,"media-studio")
        val occupied = target.listFiles().orEmpty().map(File::getName).toMutableSet()
        return buildMap {
            source.mediaFiles.sortedBy(File::getName).forEach { file ->
                val dst = File(target,file.name)
                if (dst.exists() && digest(dst)!=digest(file)) {
                    val base = file.name.substringBeforeLast('.',file.name).take(120)
                    val ext = file.name.substringAfterLast('.',"").take(20)
                    val fingerprint = digest(file).take(16)
                    var choice = "$base-$fingerprint" + (if(ext.isEmpty()) "" else ".$ext")
                    var ordinal = 1
                    while (choice in occupied) {
                        choice = "$base-$fingerprint-${ordinal++}" +
                            (if(ext.isEmpty()) "" else ".$ext")
                    }
                    put(file.name,choice)
                    occupied += choice
                } else occupied += file.name
            }
        }
    }
    private fun renameCopiedStudioReferences(database: File, files: Map<String,String>) {
        if (files.isEmpty()) return
        SQLiteDatabase.openDatabase(database.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                db.rawQuery("SELECT id,payload FROM media",null).use { rows ->
                    while(rows.moveToNext()) {
                        val id=rows.getString(0)
                        val old=rows.getString(1)
                        val rewritten=rewriteJson(old,emptyMap(),emptyMap(),files)
                        if (rewritten!=old) {
                            check(db.update("media",ContentValues().apply {
                                put("payload",rewritten)
                            },"id=?",arrayOf(id))==1) { "媒体引用重映射失败：$id" }
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    private fun createPlan(
        pending: File, snapshot: File, events: File, studio: File, full: Boolean,
    ): Plan {
        val folder = File(pending,".merge-plan")
        val ready = File(folder,"PREPARED")
        if (ready.isFile) {
            val saved=json.decodeFromString(Plan.serializer(),ready.readText())
            check(digest(File(folder,"snap.sqlite"))==saved.afterSnapshot &&
                digest(File(folder,"events.sqlite"))==saved.afterEvents &&
                digest(File(pending,"snapshot.sqlite"))==saved.sourceSnapshot &&
                digest(File(pending,"events.sqlite"))==saved.sourceEvents &&
                (!full || (saved.sourceStudio==digest(File(pending,"media-studio-stage/media-studio.sqlite")) &&
                    saved.afterStudio==digest(File(folder,"studio.sqlite"))))) {
                "已准备的合并事务字节发生变化，停止安装"
            }
            return saved
        }
        if(folder.exists()) check(folder.deleteRecursively())
        check(folder.mkdirs()) { "无法建立数据库合并暂存目录" }
        try {
            val importedSnap=File(pending,"snapshot.sqlite")
            val importedEvents=File(pending,"events.sqlite")
            val sourceSnapHash=digest(importedSnap)
            val sourceEventHash=digest(importedEvents)
            check(sourceSnapHash!="absent" && sourceEventHash!="absent") { "导入备份缺少 SQLite 文件" }
            val mergedSnap=File(folder,"snap.sqlite")
            val mergedEvents=File(folder,"events.sqlite")
            val snapshotBefore=digest(snapshot)
            val eventsBefore=digest(events)
            if(snapshotBefore=="absent") importedSnap.copyTo(mergedSnap)
            else copyLive(snapshot,mergedSnap)
            if(eventsBefore=="absent") importedEvents.copyTo(mergedEvents)
            else copyLive(events,mergedEvents)
            val localIds=ids(mergedSnap,"session_snapshots","id") +
                ids(mergedEvents,"session_event_cursors","session_id")
            val sourceIds=ids(importedSnap,"session_snapshots","id") +
                ids(importedEvents,"session_event_cursors","session_id")
            val mapping=sourceIds.intersect(localIds).associateWith {
                newId("session",sourceSnapHash+sourceEventHash,it)
            }
            require(mapping.values.none { it in localIds || it in sourceIds } &&
                mapping.values.size==mapping.values.toSet().size) { "导入会话身份重映射冲突" }
            if(snapshotBefore!="absent") mergeDatabase(mergedSnap,importedSnap,snapshotTables,
                setOf("android_metadata"),mapping)
            if(eventsBefore!="absent") mergeDatabase(mergedEvents,importedEvents,eventTables,
                eventSingletons,mapping)
            var originalStudio:String?=null
            var sourceStudio:String?=null
            var mergedStudioHash:String?=null
            var fileNames=emptyMap<String,String>()
            if(full) {
                val stageStudio=File(pending,"media-studio-stage")
                val importedStudio=File(stageStudio,"media-studio.sqlite")
                LocalMediaStudioBackupSnapshot(context,json).validate(stageStudio)
                sourceStudio=digest(importedStudio)
                originalStudio=digest(studio)
                val mergedStudio=File(folder,"studio.sqlite")
                if(originalStudio=="absent") importedStudio.copyTo(mergedStudio)
                else copyLive(studio,mergedStudio)
                fileNames=studioFileNames(stageStudio)
                if(originalStudio=="absent") {
                    renameCopiedStudioReferences(mergedStudio,fileNames)
                } else {
                    val localMedia=ids(mergedStudio,"media","id")
                    val importedMedia=ids(importedStudio,"media","id")
                    val aliases=localMedia.intersect(importedMedia).associateWith {
                        newId("media",requireNotNull(sourceStudio),it)
                    }
                    require(aliases.values.none { it in localMedia || it in importedMedia }) {
                        "媒体身份重映射冲突"
                    }
                    mergeDatabase(mergedStudio,importedStudio,listOf("metadata","media"),
                        setOf("android_metadata"),mapping,aliases,fileNames)
                }
                mergedStudioHash=digest(mergedStudio)
            }
            val plan=Plan(sourceSnapHash,sourceEventHash,sourceStudio,snapshotBefore,eventsBefore,
                originalStudio,digest(mergedSnap),digest(mergedEvents),mergedStudioHash,
                fileNames,ids(importedSnap,"session_snapshots","id").size)
            (listOf(mergedSnap,mergedEvents) +
                (if(full) listOf(File(folder,"studio.sqlite")) else emptyList()))
                .forEach(DurableFileCommit::syncFile)
            DurableFileCommit.syncDirectory(folder)
            DurableFileCommit.replace(ready,json.encodeToString(plan).toByteArray())
            DurableFileCommit.syncDirectory(folder)
            return plan
        } catch(error:Exception) {
            folder.deleteRecursively()
            throw error
        }
    }
    /** No Session owner may be running at this process-start restore boundary.
     * Settle a stopped SQLite WAL before hashing, otherwise the backup might omit
     * committed rows or a stale sidecar might be replayed onto a new main file.
     */
    private fun settleWal(file: File) {
        if (!file.exists()) return
        if (!File(file.path+"-wal").exists() && !File(file.path+"-shm").exists()) return
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use {
                check(it.moveToFirst() && it.getInt(0)==0) { "本地数据库仍被其他写入者占用" }
            }
            db.rawQuery("PRAGMA journal_mode=DELETE",null).use {
                check(it.moveToFirst() && it.getString(0).equals("delete",true)) {
                    "数据库 WAL 未完成结算"
                }
            }
        }
        check(!File(file.path+"-wal").exists()) { "数据库 WAL 结算后仍存在，暂停合并" }
    }
    private fun verifyDestination(file:File,before:String,after:String) {
        check(digest(file) in setOf(before,after)) {
            "目标数据库在合并准备后发生变化，暂停恢复且保留暂存：${file.name}"
        }
    }
    private fun installDb(source:File,destination:File,expected:String) {
        if(digest(destination)==expected) return
        destination.parentFile?.mkdirs()
        val tmp=File(destination.parentFile,".merge-${UUID.randomUUID()}")
        try {
            source.copyTo(tmp)
            check(digest(tmp)==expected) { "合并临时数据库校验失败" }
            DurableFileCommit.syncFile(tmp)
            Files.move(tmp.toPath(),destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            DurableFileCommit.syncDirectory(requireNotNull(destination.parentFile))
        } finally { tmp.delete() }
        check(digest(destination)==expected) { "合并后数据库校验失败" }
    }
    private fun installMediaFiles(pending:File,plan:Plan) {
        if(plan.sourceStudio==null) return
        val prepared=LocalMediaStudioBackupSnapshot(context,json).validate(
            File(pending,"media-studio-stage"))
        val root=File(context.filesDir,"media-studio")
        check(!Files.isSymbolicLink(root.toPath()) && (root.isDirectory || root.mkdirs())) {
            "媒体成果目录类型不安全"
        }
        val paths=prepared.mediaFiles.map { file ->
            file to File(root,plan.studioNames[file.name] ?: file.name)
        }
        paths.forEach { (source,target) ->
            if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS) &&
                    digest(target)==digest(source)) { "现有同名媒体内容冲突：${target.name}" }
            }
        }
        paths.forEach { (source,target) ->
            if(!target.exists()) {
                val tmp=File(root,".merge-${UUID.randomUUID()}")
                try {
                    source.copyTo(tmp)
                    check(digest(tmp)==digest(source)) { "合并媒体文件校验失败" }
                    DurableFileCommit.syncFile(tmp)
                    Files.move(tmp.toPath(),target.toPath())
                } finally { tmp.delete() }
            }
            DurableFileCommit.syncFile(target)
        }
        DurableFileCommit.syncDirectory(root)
    }
    fun apply(pending:File,snapshot:File,events:File,full:Boolean):Int {
        val studio=context.getDatabasePath("local_media_v1.db")
        // At startup, settle only stopped-process WAL files before any restored
        // database replaces its durable main file.
        settleWal(snapshot)
        settleWal(events)
        if(full) settleWal(studio)
        val plan=createPlan(pending,snapshot,events,studio,full)
        val folder=File(pending,".merge-plan")
        val snapMerged=File(folder,"snap.sqlite")
        val eventsMerged=File(folder,"events.sqlite")
        val studioMerged=File(folder,"studio.sqlite")
        verifyDestination(snapshot,plan.beforeSnapshot,plan.afterSnapshot)
        verifyDestination(events,plan.beforeEvents,plan.afterEvents)
        if(full) {
            verifyDestination(studio,requireNotNull(plan.beforeStudio),requireNotNull(plan.afterStudio))
            SQLiteDatabase.openDatabase(studioMerged.path,null,SQLiteDatabase.OPEN_READONLY).use {
                LocalMediaDatabase.validateBackupStructure(it)
            }
        }
        // The marker is durable before copying the first file; replay is idempotent.
        DurableFileCommit.replace(File(pending,"INSTALLING"),byteArrayOf(1))
        SqliteSessionMediaArchive(context).installStaged(pending)
        installMediaFiles(pending,plan)
        if(full) installDb(studioMerged,studio,requireNotNull(plan.afterStudio))
        installDb(snapMerged,snapshot,plan.afterSnapshot)
        installDb(eventsMerged,events,plan.afterEvents)
        return plan.importedCount
    }
}
