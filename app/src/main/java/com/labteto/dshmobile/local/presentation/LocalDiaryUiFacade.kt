package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.ChatDiaryDelta
import com.labteto.dshmobile.uicontract.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Narrow Chat Owner adapter. The UI never sees internal subject identities,
 * original message references or durable diary documents.
 */
@Singleton
class LocalDiaryUiFacade @Inject constructor(
    private val runtime: LocalUiRuntime,
) : DiaryUiPort {
    private fun subject(galleryId: String): String {
        require(galleryId.isNotBlank() && galleryId.length <= 256 &&
            galleryId.none(Char::isISOControl)) { "Invalid gallery identity" }
        return "gallery:$galleryId"
    }

    override suspend fun entries(galleryId: String): List<UiDiaryEntry> =
        withContext(Dispatchers.IO) {
            runtime.chat.diaryEntries(subject(galleryId))
                .map { record ->
                    UiDiaryEntry(
                        id = record.id, updatedAt = record.updatedAt,
                        createdAt = record.createdAt, personaName = record.personaName,
                        event = record.event, feeling = record.feeling,
                        innerThought = record.innerThought,
                        relationshipMeaning = record.relationshipMeaning,
                        unresolvedEcho = record.unresolvedEcho,
                        privateReflection = record.privateReflection,
                        active = record.active,
                        sourceMode = record.sourceMode.name,
                        experienceId = record.experienceId,
                    )
                }
        }

    override suspend fun correct(request: UiDiaryCorrection): UiDiaryReceipt =
        withContext(Dispatchers.IO) {
            try {
                val correction = ChatDiaryDelta(
                    event = request.event, feeling = request.feeling,
                    innerThought = request.innerThought,
                    relationshipMeaning = request.relationshipMeaning,
                    unresolvedEcho = request.unresolvedEcho,
                    privateReflection = request.privateReflection,
                )
                if (runtime.chat.correctDiaryEntry(
                    subject(request.galleryId), request.id, request.expectedUpdatedAt, correction
                )) UiDiaryReceipt.Applied else UiDiaryReceipt.Stale
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { UiDiaryReceipt.Failed(failure.message) }
        }

    override suspend fun deactivate(
        galleryId: String, id: String, expectedUpdatedAt: Long,
    ): UiDiaryReceipt = withContext(Dispatchers.IO) {
        try {
            if (runtime.chat.deactivateDiaryEntry(subject(galleryId), id, expectedUpdatedAt))
                UiDiaryReceipt.Applied else UiDiaryReceipt.Stale
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiDiaryReceipt.Failed(failure.message) }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalDiaryUiBindings {
    @dagger.Binds @Singleton
    abstract fun diary(implementation: LocalDiaryUiFacade): DiaryUiPort
}
