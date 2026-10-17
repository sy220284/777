package com.labteto.dshmobile.local.chat

const val PERSONA_WORD_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

enum class PersonaTransferFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    MARKDOWN("md", "text/markdown"),
    WORD("docx", PERSONA_WORD_MIME),
}

/** Opaque transfer payload; archives are decoded and committed only by the Chat owner. */
data class PersonaTransferDocument(val format: PersonaTransferFormat, val bytes: ByteArray)
