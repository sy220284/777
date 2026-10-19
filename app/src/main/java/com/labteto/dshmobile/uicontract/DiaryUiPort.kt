package com.labteto.dshmobile.uicontract

/** Diary identity is an existing gallery record ID, not a caller-provided subject key. */
data class UiDiaryEntry(
    val id: String,
    val updatedAt: Long,
    val createdAt: Long,
    val personaName: String,
    val event: String,
    val feeling: String,
    val innerThought: String,
    val relationshipMeaning: String,
    val unresolvedEcho: String,
    val privateReflection: String,
    val active: Boolean,
    val sourceMode: String,
    val experienceId: String,
)
data class UiDiaryCorrection(
    val galleryId: String,
    val id: String,
    val expectedUpdatedAt: Long,
    val event: String,
    val feeling: String,
    val innerThought: String,
    val relationshipMeaning: String,
    val unresolvedEcho: String,
    val privateReflection: String,
)
sealed interface UiDiaryReceipt {
    data object Applied : UiDiaryReceipt
    data object Stale : UiDiaryReceipt
    data class Failed(val message: String?) : UiDiaryReceipt
}
/** Projection/conditional mutation only. Chat remains the sole diary storage owner. */
interface DiaryUiPort {
    suspend fun entries(galleryId: String): List<UiDiaryEntry>
    suspend fun correct(request: UiDiaryCorrection): UiDiaryReceipt
    suspend fun deactivate(galleryId: String, id: String, expectedUpdatedAt: Long): UiDiaryReceipt
}
