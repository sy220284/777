package com.labteto.dshmobile.uicontract

/** Coordinates refer to the upright decoded image; no screen pixels cross the UI boundary. */
data class UiPortraitCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } && left < right && top < bottom)
    }
    fun pixels(width: Int, height: Int): List<Int> {
        require(width > 0 && height > 0)
        return listOf(kotlin.math.floor(left * width).toInt(), kotlin.math.floor(top * height).toInt(),
            kotlin.math.ceil(right * width).toInt(), kotlin.math.ceil(bottom * height).toInt())
    }
}

sealed interface PersonaPortraitUiCommand {
    val galleryId: String
    data class Import(override val galleryId: String, val contentUri: String, val crop: UiPortraitCrop? = null) : PersonaPortraitUiCommand
    data class Remove(override val galleryId: String) : PersonaPortraitUiCommand
}
sealed interface UiPersonaPortraitReceipt {
    data class Updated(val galleryId: String, val cleanupWarning: String? = null) : UiPersonaPortraitReceipt
    data object Missing : UiPersonaPortraitReceipt
    /** Refresh first; a storage failure can follow a committed portrait change. */
    data class Failed(val message: String?) : UiPersonaPortraitReceipt
}
sealed interface UiPersonaPortraitPreviewReceipt {
    /** Private image bytes are transient; never place them in saved UI state or logs. */
    class Ready(val bytes: ByteArray) : UiPersonaPortraitPreviewReceipt {
        override fun toString() = "Ready(<private portrait>)"
    }
    data object Missing : UiPersonaPortraitPreviewReceipt
    data class Failed(val message: String?) : UiPersonaPortraitPreviewReceipt
}
interface PersonaPortraitUiPort {
    suspend fun preview(galleryId: String): UiPersonaPortraitPreviewReceipt = UiPersonaPortraitPreviewReceipt.Failed(null)
    /** Transient native preview only; selecting/cropping does not replace the saved portrait. */
    suspend fun previewImport(contentUri: String): UiPersonaPortraitPreviewReceipt = UiPersonaPortraitPreviewReceipt.Failed("图片预览暂不可用")
    /** Owner-defined maximum size for imported/staged portrait images. */
    val maxImportBytes: Long
    suspend fun submit(command: PersonaPortraitUiCommand): UiPersonaPortraitReceipt
}
