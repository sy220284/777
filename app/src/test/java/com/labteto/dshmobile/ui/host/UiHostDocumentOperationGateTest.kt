package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiHostDocumentOperationGate
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

    @Test fun launchFailureReleasesTheOnlyPickerReservation() {
        val gate = UiHostDocumentOperationGate<String>()
        val first = requireNotNull(gate.begin("first"))
        assertTrue(gate.failToLaunch(first))
        assertNull(gate.cancelSelection())
        assertEquals("retry", gate.begin("retry")?.request)
    }
}
