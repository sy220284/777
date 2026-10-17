package com.labteto.dshmobile.local.tools.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.labteto.dshmobile.interop.mcp.McpTerminationSource
import com.labteto.dshmobile.interop.mcp.McpTransport
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Mandatory IPC hop: no local fallback to MCP network/process transport. A dead
 * Binder means an outstanding tool may have performed side effects; never replay.
 */
internal class McpPluginWorkerTransport(
    private val app: Context,
    private val kind: String,
    private val endpoint: String? = null,
    private val command: List<String> = emptyList(),
    private val cwd: File? = null,
    private val environment: Map<String, String> = emptyMap(),
) : McpTransport, McpTerminationSource {
    private val token = UUID.randomUUID().toString()
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val connected = CompletableDeferred<Messenger>()
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private var opened = false
    private val events = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 1)
    override val terminalEvents = events.asSharedFlow()
    private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.arg2 != McpWorkerWire.VERSION ||
                message.data.getString("token") != token) return
            if (message.what == McpWorkerWire.TERMINATED) {
                terminate(IOException("MCP worker terminated"))
                return
            }
            if (message.what == McpWorkerWire.RESPONSE)
                pending.remove(message.arg1)?.complete(message.data)
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) terminate(IOException("MCP worker binder missing"))
            else if (!closed.get()) connected.complete(Messenger(binder))
        }
        override fun onServiceDisconnected(name: ComponentName?) =
            terminate(IOException("MCP worker process disconnected"))
        override fun onBindingDied(name: ComponentName?) =
            terminate(IOException("MCP worker binding died"))
        override fun onNullBinding(name: ComponentName?) =
            terminate(IOException("MCP worker returned null binder"))
    }

    private fun terminate(cause: IOException) {
        if (!closed.compareAndSet(false, true)) return
        events.tryEmit(cause)
        if (!connected.isCompleted) connected.completeExceptionally(cause)
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        if (bound.compareAndSet(true, false))
            runCatching { app.unbindService(connection) }
    }

    private suspend fun ensureOpened() = mutex.withLock {
        check(!closed.get()) { "MCP isolated worker is closed; explicit reconnect required" }
        if (opened) return@withLock
        withContext(Dispatchers.Main.immediate) {
            if (bound.compareAndSet(false, true)) {
                val started = try {
                    app.bindService(
                        Intent(app, McpPluginWorkerService::class.java),
                        connection, Context.BIND_AUTO_CREATE,
                    )
                } catch (failure: SecurityException) {
                    terminate(IOException("MCP worker service not permitted", failure))
                    throw failure
                }
                if (!started) terminate(IOException("MCP worker bind rejected"))
            }
        }
        withTimeout(McpWorkerWire.CONNECT_TIMEOUT_MS) { connected.await() }
        val request = Bundle().apply {
            putString("kind", kind)
            when (kind) {
                "http" -> putString("endpoint", requireNotNull(endpoint))
                "stdio" -> {
                    putStringArrayList("command", ArrayList(command))
                    putString("cwd", cwd?.absolutePath)
                    putStringArrayList("env", ArrayList(environment.map { "${it.key}=${it.value}" }))
                }
                else -> error("unsupported MCP worker kind")
            }
        }
        rpc(McpWorkerWire.OPEN, request)
        opened = true
    }

    private suspend fun rpc(kind: Int, payload: Bundle): Bundle {
        if (closed.get()) throw IOException("MCP worker closed; outcome unknown")
        if (pending.size >= McpWorkerWire.MAX_PENDING_CALLS)
            throw IOException("MCP IPC concurrent-call limit exceeded")
        val seq = ids.getAndIncrement()
        val result = CompletableDeferred<Bundle>()
        pending[seq] = result
        val msg = Message.obtain(null, kind, seq, McpWorkerWire.VERSION).apply {
            replyTo = reply
            data = Bundle(payload).apply { putString("token", token) }
        }
        try {
            connected.await().send(msg)
            val response = withTimeout(McpWorkerWire.REQUEST_TIMEOUT_MS) { result.await() }
            val error = response.getString("error")
            if (error != null) {
                if (response.getBoolean("transport_error"))
                    throw IOException("MCP worker transport failed ($error); outcome unknown")
                throw IllegalStateException("MCP worker refused request ($error)")
            }
            return response
        } catch (cancelled: CancellationException) {
            // Cancellation might race an already committed remote side effect.
            throw cancelled
        } catch (error: android.os.RemoteException) {
            terminate(IOException("MCP IPC disconnected; outcome unknown", error))
            throw IOException("MCP IPC disconnected; outcome unknown", error)
        } finally {
            pending.remove(seq)
        }
    }

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        require(McpWorkerWire.isAllowedMethod(method))
        val encoded = Json.encodeToString(JsonObject.serializer(), params)
        require(McpWorkerWire.validRequestSize(encoded)) { "MCP request exceeds Binder budget" }
        ensureOpened()
        val result = rpc(McpWorkerWire.REQUEST, Bundle().apply {
            putString("method", method)
            putString("params", encoded)
        }).getString("result") ?: throw IOException("MCP worker returned no result")
        require(McpWorkerWire.validResponseSize(result)) { "MCP reply exceeds Binder budget" }
        return Json.parseToJsonElement(result) as? JsonObject
            ?: throw IOException("MCP worker returned invalid reply")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(
            IOException("MCP transport closed; pending call outcome unknown")) }
        pending.clear()
        if (opened && connected.isCompleted) {
            runCatching {
                // Service connection owns its own isolated transport instance.
                // CLOSE is best effort only; no attempt to replay pending tool calls.
                val message = Message.obtain(null, McpWorkerWire.CLOSE, ids.getAndIncrement(),
                    McpWorkerWire.VERSION).apply {
                    replyTo = reply
                    data = Bundle().apply { putString("token", token) }
                }
                connected.getCompleted().send(message)
            }
        }
        if (bound.compareAndSet(true, false))
            runCatching { app.unbindService(connection) }
    }
}
