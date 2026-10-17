package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPersonaArchiveMutationCoordinatorTest {
    private val save = PersonaStoryUiCommand.SaveDetails(null, "gallery", "story", "notes", "stage")
    private val delete = DeletePersonaGalleryCommand(null, "gallery")

    @Test fun committedStoryWithDeferredSessionStillRefreshesExactlyOnceWithoutReplayingMutation() = runTest {
        var submitted = 0
        var refreshed = 0
        val owner = LocalPersonaArchiveMutationCoordinator(
            stories = object : PersonaStoryUiPort {
                override suspend fun submit(command: PersonaStoryUiCommand): UiPersonaStoryReceipt {
                    submitted++
                    assertEquals(save, command)
                    return UiPersonaStoryReceipt.Saved(UiStorySessionSync.DEFERRED)
                }
            },
            gallery = unusedGallery(),
            refresh = { refreshed++ },
        )
        val result = owner.saveStory(save)
        assertEquals(LocalPersonaArchiveMutationCoordinator.Receipt.Committed(true), result)
        assertEquals(1, submitted)
        assertEquals(1, refreshed)
    }

    @Test fun noRefreshAfterRejectedMutationAndNoRepairReplayAfterFailedRefresh() = runTest {
        var submitted = 0
        var refreshed = 0
        val owner = LocalPersonaArchiveMutationCoordinator(
            stories = object : PersonaStoryUiPort {
                override suspend fun submit(command: PersonaStoryUiCommand): UiPersonaStoryReceipt {
                    submitted++
                    return if (submitted == 1) UiPersonaStoryReceipt.Missing
                        else UiPersonaStoryReceipt.Saved(UiStorySessionSync.APPLIED)
                }
            },
            gallery = unusedGallery(),
            refresh = { refreshed++; error("UI cache temporarily unavailable") },
        )
        assertEquals(LocalPersonaArchiveMutationCoordinator.Receipt.Missing, owner.saveStory(save))
        assertEquals(0, refreshed)
        assertEquals(LocalPersonaArchiveMutationCoordinator.Receipt.Committed(true), owner.saveStory(save))
        assertEquals(2, submitted)
        assertEquals(1, refreshed)
    }

    @Test fun deletedGalleryCarriesCleanupWarningsAndNeverConflatesSessionSync() = runTest {
        var deleted = 0
        val owner = LocalPersonaArchiveMutationCoordinator(
            stories = unusedStory(),
            gallery = object : PersonaGalleryDeletionUiPort {
                override suspend fun delete(command: DeletePersonaGalleryCommand): UiPersonaGalleryDeletionReceipt {
                    deleted++
                    assertEquals(this@LocalPersonaArchiveMutationCoordinatorTest.delete, command)
                    return UiPersonaGalleryDeletionReceipt.Deleted(
                        UiStorySessionSync.NOT_BOUND, portraitCleanupWarning = "orphan portrait pending")
                }
            },
            refresh = {},
        )
        val result = owner.deleteGallery(delete)
        assertEquals(LocalPersonaArchiveMutationCoordinator.Receipt.Committed(
            false, "orphan portrait pending"), result)
        assertEquals(1, deleted)
    }

    @Test fun cancellationMustNotBeReportedAsSuccessfulArchiveRepair() = runTest {
        val owner = LocalPersonaArchiveMutationCoordinator(
            stories = object : PersonaStoryUiPort {
                override suspend fun submit(command: PersonaStoryUiCommand) =
                    UiPersonaStoryReceipt.Saved(UiStorySessionSync.APPLIED)
            },
            gallery = unusedGallery(),
            refresh = { throw CancellationException("screen detached") },
        )
        val result = runCatching { owner.saveStory(save) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(result.isSuccess)
    }

    private fun unusedStory() = object : PersonaStoryUiPort {
        override suspend fun submit(command: PersonaStoryUiCommand): UiPersonaStoryReceipt =
            error("unexpected mutation")
    }
    private fun unusedGallery() = object : PersonaGalleryDeletionUiPort {
        override suspend fun delete(command: DeletePersonaGalleryCommand): UiPersonaGalleryDeletionReceipt =
            error("unexpected deletion")
    }
}
