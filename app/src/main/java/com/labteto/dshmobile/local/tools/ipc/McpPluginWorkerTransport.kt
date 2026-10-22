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
import android.os.ParcelFileDescriptor
import com.labteto.dshmobile.interop.mcp.McpHttpException
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
    private val bearerCredential: String? = null,
    private val credentialProvider: (suspend () -> String?)? = null,
    private val authChallenge: ((String?, String?) -> Unit)? = null,
) : McpTransport, McpTerminationSource {
    private val token = UUID.randomUUID().toString()
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val connected = CompletableDeferred<Messenger>()
    @Volatile private var remoteMessenger: Messenger? = null
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private var opened = false
    private val credentialMutex = Mutex()
    private var transmittedCredential: String? = bearerCredential

    private suspend fun refreshCredential() {
        val provider = credentialProvider ?: return
        credentialMutex.withLock {
            val current = provider()
            if (current == null && transmittedCredential != null)
                error("MCP 认证已清除，请重新连接。")
            if (current == transmittedCredential) return@withLock
            rpc(McpWorkerWire.UPDATE_AUTH, Bundle().apply { putString("bearer_credential", current) })
            transmittedCredential = current
        }
    }
    internal var workerPid: Int? = null
        private set
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
            else if (!closed.get()) {
                val peer = Messenger(binder)
                remoteMessenger = peer
                connected.complete(peer)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) =
            terminate(IOException("MCP worker process disconnected"))
        override fun onBindingDied(name: ComponentName?) =
            terminate(IOException("MCP worker binding died"))
        override fun onNullBinding(name: ComponentName?) =
            terminate(IOException("MCP worker returned null binder"))
    }

    private fun sendCloseWithoutWaiting() {
        runCatching {
            remoteMessenger?.send(Message.obtain(null, McpWorkerWire.CLOSE,
                ids.getAndIncrement(), McpWorkerWire.VERSION).apply {
                replyTo = reply
                data = Bundle().apply { putString("token", token) }
            })
        }
    }

    private fun terminate(cause: IOException) {
        if (!closed.compareAndSet(false, true)) return
        sendCloseWithoutWaiting()
        events.tryEmit(cause)
        if (!connected.isCompleted) connected.completeExceptionally(cause)
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        if (bound.compareAndSet(true, false))
            runCatching { app.unbindService(connection) }
    }

    private suspend fun ensureOpened() {
        try {
            mutex.withLock {
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
                "http" -> {
                    putString("endpoint", requireNotNull(endpoint))
                    bearerCredential?.let { putString("bearer_credential", it) }
                }
                "stdio" -> {
                    putStringArrayList("command", ArrayList(command))
                    putString("cwd", cwd?.absolutePath)
                    putStringArrayList("env", ArrayList(environment.map { "${it.key}=${it.value}" }))
                }
                else -> error("unsupported MCP worker kind")
            }
        }
        val openedResult = rpc(McpWorkerWire.OPEN, request)
        val pid = Json.parseToJsonElement(openedResult.getString("result") ?: "{}")
            .let { it as? JsonObject }?.get("worker_pid")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
            ?: throw IOException("MCP worker identity is missing")
        if (!McpWorkerWire.isDedicatedProcess(android.os.Process.myPid(), pid)) {
            terminate(IOException("MCP worker is not running in a separate OS process"))
            throw IOException("MCP worker isolation unavailable")
        }
        workerPid = pid
        opened = true
            }
        } catch (cancelled: CancellationException) {
            terminate(IOException("MCP worker open cancelled; explicit reconnect required"))
            throw cancelled
        } catch (failure: Exception) {
            terminate(IOException("MCP worker open failed; no in-process fallback", failure))
            throw failure
        }
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
                val status = response.getInt("http_status", 0)
                if (status != 0) {
                    val metadata = response.getString("auth_resource_metadata")
                    val scope = response.getString("auth_scope")
                    if (status == 401 || status == 403) authChallenge?.invoke(metadata, scope)
                    throw McpHttpException(status, "Remote MCP HTTP failure", metadata, scope)
                }
                if (response.getBoolean("transport_error"))
                    throw IOException("MCP worker transport failed ($error); outcome unknown")
                throw IllegalStateException("MCP worker refused request ($error)")
            }
            return response
        } catch (cancelled: CancellationException) {
            // Timeouts and explicit cancellation can race a committed remote write.
            // Evict this worker instance and revoke its tools. The caller MUST NOT
            // transparently retry the same paid or irreversible operation.
            terminate(IOException("MCP request cancelled or timed out; outcome unknown"))
            throw cancelled
        } catch (error: android.os.RemoteException) {
            terminate(IOException("MCP IPC disconnected; outcome unknown", error))
            throw IOException("MCP IPC disconnected; outcome unknown", error)
        } finally {
            pending.remove(seq)
        }
    }

    override suspend fun request(method: String, params: JsonObject): JsonObject = try {
        require(McpWorkerWire.isAllowedMethod(method))
        val encoded = Json.encodeToString(JsonObject.serializer(), params)
        require(McpWorkerWire.validRequestSize(encoded)) { "MCP request exceeds Binder budget" }
        ensureOpened()
        refreshCredential()
        val replyBundle = rpc(McpWorkerWire.REQUEST, Bundle().apply {
            putString("method", method)
            putString("params", encoded)
        })
        val result = replyBundle.getString("result") ?: replyBundle
            .getParcelable("result_pipe", ParcelFileDescriptor::class.java)?.let { pipe ->
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                        val limit = McpWorkerWire.MAX_RESPONSE_BYTES
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(out.size() + n <= limit) { "MCP pipe response exceeded budget" }
                            out.write(buffer, 0, n)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                }
            } ?: throw IOException("MCP worker returned no result")
        require(McpWorkerWire.validResponseSize(result)) { "MCP reply exceeds verified budget" }
        Json.parseToJsonElement(result) as? JsonObject
            ?: throw IOException("MCP worker returned invalid reply")
    } catch (failure: IOException) {
        // The error result can overtake the asynchronous TERMINATED notice.
        // Abort every sibling IPC call now; there is no automatic retry.
        terminate(failure)
        throw failure
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(
            IOException("MCP transport closed; pending call outcome unknown")) }
        pending.clear()
        if (connected.isCompleted) sendCloseWithoutWaiting()
        if (bound.compareAndSet(true, false))
            runCatching { app.unbindService(connection) }
    }
}
