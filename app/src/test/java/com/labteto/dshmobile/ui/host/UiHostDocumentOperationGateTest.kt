package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiHostDocumentOperationGate
import com.labteto.dshmobile.uihost.UiHostPlatformAction
import com.labteto.dshmobile.uihost.UiHostSystemOperationLease
import com.labteto.dshmobile.uihost.UiHostSystemOperationOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiHostDocumentOperationGateTest {
    @Test fun pickerReturnKeepsRequestOwnedUntilOutputStreamAndChooserComplete() {
        val gate = UiHostDocumentOperationGate<String>()
        val request = requireNotNull(gate.begin("report.pdf"))
        assertEquals(UiHostDocumentOperationGate.Phase.WAITING_FOR_PICKER, gate.phase)
        assertNull(gate.begin("second.pdf"))
        assertEquals(request, gate.selectionReturned())
        assertEquals(UiHostDocumentOperationGate.Phase.TRANSFERRING, gate.phase)
        assertNull(gate.begin("second.pdf"))
        assertNull(gate.cancelSelection())
        assertTrue(gate.finish(request))
        assertEquals(UiHostDocumentOperationGate.Phase.IDLE, gate.phase)
        assertEquals("second.pdf", gate.begin("second.pdf")?.request)
    }

    @Test fun cancelledPickerInvalidatesOnlyItsOwnRequestAndStaleCallbacksCannotStealNewOne() {
        val gate = UiHostDocumentOperationGate<Int>()
        val first = requireNotNull(gate.begin(1))
        assertEquals(first, gate.cancelSelection())
        val second = requireNotNull(gate.begin(2))
        assertFalse(gate.finish(first))
        assertEquals(second, gate.selectionReturned())
        assertFalse(gate.failToLaunch(second))
        assertTrue(gate.finish(second))
        assertNull(gate.selectionReturned())
    }

    @Test fun processRecreationRejectsOldPickerTicketsEvenForTheSameRequest() {
        val original = UiHostDocumentOperationGate<String>()
        val stale = requireNotNull(original.begin("same.txt"))
        val restored = UiHostDocumentOperationGate<String>()
        val current = requireNotNull(restored.begin("same.txt"))
        assertFalse(restored.finish(stale))
        assertEquals(current, restored.selectionReturned())
        assertTrue(restored.finish(current))
    }

    @Test fun disposedHostRevokesUnreturnedPickerBeforeAnotherFeatureUsesOwner() {
        val owner = UiHostSystemOperationOwner()
        val importer = UiHostSystemOperationLease(owner, UiHostPlatformAction.IMPORT_DOCUMENT)
        val exporter = UiHostSystemOperationLease(owner, UiHostPlatformAction.EXPORT_WORKSPACE)
        val first = UiHostDocumentOperationGate<String>()
        assertTrue(importer.begin())
        val abandoned = requireNotNull(first.begin("private-document.txt"))
        assertFalse(exporter.begin())
        // Matches DisposableEffect teardown: cancel the selection before releasing
        // the shared Host Owner. A delayed result must not read the old document.
        assertEquals(abandoned, first.cancelSelection())
        assertTrue(importer.finish())
        assertNull(first.selectionReturned())
        assertFalse(first.finish(abandoned))
        assertTrue(exporter.begin())
        assertFalse(importer.begin())
        assertTrue(exporter.finish())
        assertNull(owner.activeAction())
    }

    @Test fun launchFailureReleasesTheOnlyPickerReservation() {
        val gate = UiHostDocumentOperationGate<String>()
        val first = requireNotNull(gate.begin("first"))
        assertTrue(gate.failToLaunch(first))
        assertNull(gate.cancelSelection())
        assertEquals("retry", gate.begin("retry")?.request)
    }
}
