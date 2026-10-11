package com.labteto.dshmobile.local.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkspaceReadWriteGateTest {
    @Test
    fun readersOverlapButWriterMustWaitForEveryReaderToRelease() = runTest {
        val gate = LocalWorkspaceReadWriteGate()
        val bothReadersEntered = CompletableDeferred<Unit>()
        val releaseReaders = CompletableDeferred<Unit>()
        var entered = 0
        val first = async {
            gate.withRead {
                entered += 1
                releaseReaders.await()
            }
        }
        val second = async {
            gate.withRead {
                entered += 1
                bothReadersEntered.complete(Unit)
                releaseReaders.await()
            }
        }
        bothReadersEntered.await()
        assertEquals(2, entered)

        var writerEntered = false
        val writer = async { gate.withWrite { writerEntered = true } }
        runCurrent()
        assertFalse(writerEntered)
        releaseReaders.complete(Unit)
        first.await()
        second.await()
        writer.await()
        assertTrue(writerEntered)
    }

    @Test
    fun newlyArrivingReaderMustNotOvertakeAnAlreadyWaitingWriter() = runTest {
        val gate = LocalWorkspaceReadWriteGate()
        val releaseFirst = CompletableDeferred<Unit>()
        val enteredFirst = CompletableDeferred<Unit>()
        val first = async { gate.withRead {
            enteredFirst.complete(Unit)
            releaseFirst.await()
        } }
        enteredFirst.await()
        val order = mutableListOf<String>()
        val writer = async { gate.withWrite { order += "write" } }
        runCurrent()
        val laterReader = async { gate.withRead { order += "read" } }
        runCurrent()
        releaseFirst.complete(Unit)
        first.await()
        writer.await()
        laterReader.await()
        assertEquals(listOf("write", "read"), order)
    }
}
