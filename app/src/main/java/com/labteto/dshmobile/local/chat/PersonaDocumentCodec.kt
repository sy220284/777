package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** V4-only exchange; importing an old share or archive is deliberately unsupported. */
internal sealed interface PersonaDocumentImport {
    data class Archive(
        val entry: PersonaGalleryEntry,
        val diaryEntries: List<ChatDiaryEntry> = emptyList(),
    ) : PersonaDocumentImport
    data object Share : PersonaDocumentImport
}

internal object PersonaDocumentCodec {
    fun decodeDocumentImport(json: Json, payload: String): PersonaDocumentImport {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw IllegalArgumentException("人物文件格式无效", it) }
        require(root["schema"]?.jsonPrimitive?.intOrNull == 4) {
            "人物文件版本不受支持，请使用V4人物档案"
        }
        return when {
            "entry" in root -> PersonaTransferDocuments.decodeArchive(json, payload).let {
                PersonaDocumentImport.Archive(it.entry, it.diaryEntries)
            }
            "persona" in root -> PersonaDocumentImport.Share
            else -> throw IllegalArgumentException("人物文件缺少档案内容")
        }
    }

    fun decodeShare(json: Json, payload: String): PersonaProfile {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw IllegalArgumentException("人物分享内容无效", it) }
        require(root["schema"]?.jsonPrimitive?.intOrNull == 4 && "persona" in root) {
            "只支持V4人物分享"
        }
        return runCatching { json.decodeFromString(PersonaShareEnvelope.serializer(), payload).persona }
            .getOrElse { throw IllegalArgumentException("人物分享数据格式不正确", it) }
    }
}
