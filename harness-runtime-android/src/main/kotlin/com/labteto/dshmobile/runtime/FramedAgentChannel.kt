package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.agent.AgentBackendChannel
import com.labteto.dshmobile.harness.agent.AgentBackendProtocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Descriptor/socket streams, not Binder payloads: large histories do not hit Binder's 1 MiB limit. */
class FramedAgentChannel(
    input: InputStream, output: OutputStream,
    private val maxFrameBytes: Int = 32 * 1024 * 1024,
    private val onClose: () -> Unit = {},
) : com.labteto.dshmobile.harness.agent.AgentBackendChannel {
    private val reader = DataInputStream(input)
    private val writer = DataOutputStream(output)
    private val outputMutex = Mutex()
    private val inputMutex = Mutex()
    private val closed = AtomicBoolean()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun <T> io(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        ioScope.launch {
            try { continuation.resume(block()) }
            catch (error: Exception) { continuation.resumeWithException(error) }
        }
        // Close on cancellation *before* joining a blocked native read/write.
        continuation.invokeOnCancellation { close() }
    }

    override suspend fun send(frame: JsonObject) = outputMutex.withLock {
        val bytes = frame.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFrameBytes) { "AGENT_BACKEND_FRAME_TOO_LARGE" }
        io { writer.writeInt(bytes.size); writer.write(bytes); writer.flush() }
    }

    override suspend fun receive(): JsonObject = inputMutex.withLock {
        io {
            val size = reader.readInt()
            require(size in 1..maxFrameBytes) { "AGENT_BACKEND_FRAME_SIZE_INVALID" }
            val data = ByteArray(size)
            reader.readFully(data)
            AgentBackendProtocol.json.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Socket close/unbind first: releases writers before closing their buffered streams.
        try { onClose() } finally {
            runCatching { reader.close() }; runCatching { writer.close() }; ioScope.cancel()
        }
    }
}
