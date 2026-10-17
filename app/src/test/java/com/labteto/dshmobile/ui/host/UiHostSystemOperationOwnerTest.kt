package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiHostPlatformAction
import com.labteto.dshmobile.uihost.UiHostSystemOperationLease
import com.labteto.dshmobile.uihost.UiHostSystemOperationOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One Host controls every native picker / provider stream across all Scenes. */
class UiHostSystemOperationOwnerTest {
    @Test fun permissionWindowUsesTheSameOwnerAsFilesAndLateResultCannotFinishAnotherWindow() {
        val owner = UiHostSystemOperationOwner()
        val permission = requireNotNull(owner.begin(UiHostPlatformAction.REQUEST_PERMISSION))
        assertNull(owner.begin(UiHostPlatformAction.PICK_ATTACHMENT))
        assertNull(owner.begin(UiHostPlatformAction.SAVE_DOCUMENT))
        assertTrue(owner.finish(permission))
        val document = requireNotNull(owner.begin(UiHostPlatformAction.PICK_ATTACHMENT))
        assertFalse(owner.finish(permission))
        assertEquals(UiHostPlatformAction.PICK_ATTACHMENT, owner.activeAction())
        assertTrue(owner.finish(document))
    }

    @Test fun attachmentPickerBlocksWorkspaceExportAndDocumentSaveUntilOperationCompletes() {
        val owner = UiHostSystemOperationOwner()
        val attachment = UiHostSystemOperationLease(owner, UiHostPlatformAction.PICK_ATTACHMENT)
        val saver = UiHostSystemOperationLease(owner, UiHostPlatformAction.SAVE_DOCUMENT)
        val exporter = UiHostSystemOperationLease(owner, UiHostPlatformAction.EXPORT_WORKSPACE)
        assertTrue(attachment.begin())
        assertFalse(saver.begin())
        assertFalse(exporter.begin())
        assertEquals(UiHostPlatformAction.PICK_ATTACHMENT, owner.activeAction())
        assertTrue(attachment.finish())
        assertTrue(exporter.begin())
        assertFalse(saver.begin())
        // The lease remains owned after the picker result through the async write.
        assertEquals(UiHostPlatformAction.EXPORT_WORKSPACE, owner.activeAction())
        assertTrue(exporter.finish())
        assertTrue(saver.begin())
        assertTrue(saver.finish())
        assertNull(owner.activeAction())
    }

    @Test fun staleTicketCannotCompleteNewOwnerTransactionOrReleaseOtherScene() {
        val owner = UiHostSystemOperationOwner()
        val previous = requireNotNull(owner.begin(UiHostPlatformAction.IMPORT_DOCUMENT))
        assertTrue(owner.finish(previous))
        val current = requireNotNull(owner.begin(UiHostPlatformAction.EXPORT_WORKSPACE))
        assertFalse(owner.finish(previous))
        assertEquals(UiHostPlatformAction.EXPORT_WORKSPACE, owner.activeAction())
        assertTrue(owner.finish(current))
        assertNull(owner.activeAction())
    }

    @Test fun unlaunchedPickerAndHostCancellationCannotLeaveAnOrphanLease() {
        val owner = UiHostSystemOperationOwner()
        val first = UiHostSystemOperationLease(owner, UiHostPlatformAction.SAVE_DOCUMENT)
        assertTrue(first.begin())
        assertTrue(first.finish())
        assertFalse(first.finish())
        assertNotNull(owner.begin(UiHostPlatformAction.SHARE_WORKSPACE))
    }

    @Test fun concurrentFeatureRequestsCannotBothEnterAndroidPicker() {
        val owner = UiHostSystemOperationOwner()
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val start = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val workers = List(2) { index ->
            Thread {
                val lease = UiHostSystemOperationLease(owner,
                    if (index == 0) UiHostPlatformAction.IMPORT_DOCUMENT
                    else UiHostPlatformAction.EXPORT_WORKSPACE)
                assertTrue(start.await(3, TimeUnit.SECONDS))
                val started = lease.begin()
                outcomes.add(started)
                done.countDown()
                if (started) {
                    release.await(3, TimeUnit.SECONDS)
                    lease.finish()
                }
            }.apply { start() }
        }
        start.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, outcomes.count { it })
        release.countDown()
        workers.forEach { it.join(1_000) }
        assertNull(owner.activeAction())
    }
}
