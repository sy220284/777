package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uihost.UiDocumentWriteReceipt
import com.labteto.dshmobile.uihost.UiHostDocumentOperationGate
import com.labteto.dshmobile.uihost.UiHostPlatformAction
import com.labteto.dshmobile.uihost.UiHostSystemOperationLease
import com.labteto.dshmobile.uihost.UiHostSystemOperationOwner
import com.labteto.dshmobile.uihost.writeUiDocumentStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UiHostStreamDocumentSaveTest {
    private class Output(private val failOnClose: Boolean = false) : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
            if (failOnClose) throw IOException("provider close failed")
            super.close()
        }
    }

    @Test fun mediaSaveKeepsOriginalSessionAndBlocksOtherPickersThroughStreamClose() = runBlocking {
        val owner = UiHostSystemOperationOwner()
        val save = UiHostSystemOperationLease(owner, UiHostPlatformAction.SAVE_DOCUMENT)
        val portrait = UiHostSystemOperationLease(owner, UiHostPlatformAction.PICK_ATTACHMENT)
        val gate = UiHostDocumentOperationGate<Pair<String, String>>()
        var selection = "asset-a" to "session-a"
        assertTrue(save.begin())
        val ticket = requireNotNull(gate.begin(selection))
        selection = "asset-b" to "session-b"
        assertEquals(ticket, gate.selectionReturned())
        val output = Output()
        val result = writeUiDocumentStream(ticket.request, { output }) { request, stream ->
            assertEquals("asset-a" to "session-a", request)
            assertFalse(portrait.begin())
            assertNull(gate.begin(selection))
            stream.write("original media".toByteArray())
            UiDocumentWriteReceipt.Saved
        }
        assertEquals(UiDocumentWriteReceipt.Saved, result)
        assertTrue(output.closed)
        assertEquals("original media", output.toString("UTF-8"))
        assertTrue(gate.finish(ticket))
        assertTrue(save.finish())
        assertTrue(portrait.begin())
        assertTrue(portrait.finish())
    }

    @Test fun featureRejectionIsPreservedAndDestinationIsClosed() = runBlocking {
        val output = Output()
        val rejected = UiDocumentWriteReceipt.Failed("session access revoked")
        assertEquals(rejected, writeUiDocumentStream("asset", { output }) { _, _ -> rejected })
        assertEquals(0, output.size())
        assertTrue(output.closed)
    }

    @Test fun providerCloseFailureCannotReturnSaved() = runBlocking {
        val output = Output(failOnClose = true)
        try {
            writeUiDocumentStream("asset", { output }) { _, stream ->
                stream.write(1)
                UiDocumentWriteReceipt.Saved
            }
            fail("closing the destination must finish before success")
        } catch (failure: IOException) {
            assertEquals("provider close failed", failure.message)
        }
        assertTrue(output.closed)
    }

    @Test fun cancellationClosesStreamWithoutRetryingOwnerWrite() = runBlocking {
        val output = Output()
        var calls = 0
        try {
            writeUiDocumentStream("asset", { output }) { _, _ ->
                calls++
                throw CancellationException("scene left")
            }
            fail("cancellation must propagate")
        } catch (failure: CancellationException) {
            assertEquals("scene left", failure.message)
        }
        assertEquals(1, calls)
        assertTrue(output.closed)
    }

    @Test fun portraitResultKeepsPersonChosenBeforePickerAndRecreatedHostCannotReuseIt() {
        val gate = UiHostDocumentOperationGate<String>()
        var selected = "person-a"
        gate.begin(selected)
        selected = "person-b"
        val result = requireNotNull(gate.cancelSelection())
        assertEquals("person-a", result.request)
        assertEquals("person-b", selected)
        assertNull(UiHostDocumentOperationGate<String>().cancelSelection())
        assertNull(gate.cancelSelection())
    }
}
