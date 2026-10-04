package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryPreparedImport
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.presentation.LocalUiRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps persona transfer orchestration out of the gallery UI state owner. */
internal class LocalPersonaTransferCoordinator(
    private val runtime: LocalUiRuntime,
    private val galleryStore: ChatPersonaGalleryStore,
) {
    suspend fun export(id: String, format: PersonaTransferFormat): PersonaTransferDocument =
        withContext(Dispatchers.IO) {
            val diaryEntries = runtime.chat.diaryEntriesForTransfer("gallery:$id")
            galleryStore.exportPersonaDocument(id, format, diaryEntries)
        }

    suspend fun import(
        bytes: ByteArray,
        fileName: String?,
        mimeType: String?,
    ): PersonaGalleryEntry = withContext(Dispatchers.IO) {
        when (val prepared = galleryStore.preparePersonaDocumentImport(bytes, fileName, mimeType)) {
            is PersonaGalleryPreparedImport.Share -> galleryStore.commitPersonaDocumentImport(prepared)
            is PersonaGalleryPreparedImport.Archive -> runtime.chat.importDiaryEntriesForTransfer(
                subjectKey = "gallery:${prepared.entry.id}",
                personaName = prepared.entry.persona.name,
                entries = prepared.diaryEntries,
            ) {
                galleryStore.commitPersonaDocumentImport(prepared)
            }
        }
    }
}
