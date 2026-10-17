package com.labteto.dshmobile.local.attachment



@kotlinx.serialization.Serializable
data class LocalImportedAttachment(
    val name: String,
    val relativePath: String,
    val mediaType: String,
    val bytes: Long,
    val attachmentId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)
