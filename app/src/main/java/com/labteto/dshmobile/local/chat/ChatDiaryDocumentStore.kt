package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.persistence.RecoveringDocumentFile
import com.labteto.dshmobile.local.persistence.DocumentFileStamp
import java.io.File
import kotlinx.serialization.json.Json

/** Diary owns its document; shared persistence owns durable replacement and fail-closed recovery. */
internal class ChatDiaryDocumentStore(private val root: File, private val json: Json) {
    private val file = File(root, "diary.json")
    private val durable = RecoveringDocumentFile(file)
    private var cached: ChatDiaryDocument? = null
    private var cachedStamp: DocumentFileStamp? = null

    @Synchronized
    fun read(): ChatDiaryDocument {
        cached?.takeIf { cachedStamp == stamp() }?.let { return it }
        val document = durable.read(::ChatDiaryDocument, ::decode)
        cached = document
        cachedStamp = stamp()
        return document
    }

    @Synchronized
    fun write(document: ChatDiaryDocument) {
        durable.write(json.encodeToString(ChatDiaryDocument.serializer(), document)) {
            runCatching { decode(it) }.isSuccess
        }
        cached = document
        cachedStamp = stamp()
    }

    private fun decode(encoded: String) = json.decodeFromString(ChatDiaryDocument.serializer(), encoded)

    private fun stamp() = DocumentFileStamp.of(file, File(root, "diary.json.bak"),
        File(root, "diary.json.recovery-required"))
}
