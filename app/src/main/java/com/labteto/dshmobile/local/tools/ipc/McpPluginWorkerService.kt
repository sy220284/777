package com.labteto.dshmobile.local.tools.ipc

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import com.labteto.dshmobile.interop.mcp.McpNegotiatingHttpTransport
import com.labteto.dshmobile.interop.mcp.McpNegotiatingStdioTransport
import com.labteto.dshmobile.interop.mcp.McpTerminationSource
import com.labteto.dshmobile.interop.mcp.McpTransport
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * Non-exported, dedicated :mcp_worker OS process. Only MCP wire commands execute here;
 * the tool registry, authorizations, model prompts and Work state never cross this Binder.
 *
 * This is process/failure isolation (same app UID), NOT a separate-UID permission sandbox.
 */
class McpPluginWorkerService : Service() {
    private class Instance(val transport: McpTransport) {
        @Volatile var terminalObserver: Job? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val instances = ConcurrentHashMap<String, Instance>()
    private val json = Json
    private val http = OkHttpClient()
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what !in setOf(McpWorkerWire.OPEN, McpWorkerWire.REQUEST,
                    McpWorkerWire.CLOSE)) return
            val sender = message.replyTo ?: return
            val requestId = message.arg1
            val operation = message.what
            val fields = Bundle(message.data).apply { classLoader = javaClass.classLoader }
            val token = fields.getString("token") ?: return
            if (token.length !in 20..100) return
            scope.launch {
                try {
                    when (operation) {
                        McpWorkerWire.OPEN -> {
                            check(token !in instances) { "IPC transport identity already exists" }
                            check(instances.size < 24) { "IPC transport budget exhausted" }
                            val transport = when (fields.getString("kind")) {
                                "http" -> McpNegotiatingHttpTransport(
                                    requireNotNull(fields.getString("endpoint")),
                                    http, json,
                                )
                                "stdio" -> {
                                    val command = fields.getStringArrayList("command").orEmpty()
                                    require(command.size in 1..32 && command.all { it.length <= 2048 })
                                    val cwd = fields.getString("cwd")?.let(::File)
                                    val environment = fields.getStringArrayList("env").orEmpty()
                                        .associate { row ->
                                            val split = row.indexOf('=')
                                            require(split in 1..128) { "invalid process environment" }
                                            row.substring(0, split) to row.substring(split + 1)
                                        }
                                    McpNegotiatingStdioTransport(
                                        command = command, workingDirectory = cwd,
                                        json = json, commandResolver = { it },
                                        environmentProvider = { environment },
                                    )
                                }
                                else -> error("unknown worker transport")
                            }
                            val instance = Instance(transport)
                            check(instances.putIfAbsent(token, instance) == null) {
                                transport.close()
                                "IPC duplicate transport"
                            }
                            send(sender, McpWorkerWire.RESPONSE, requestId, token,
                                result = "{\"worker_pid\":${android.os.Process.myPid()}}")
                        }
                        McpWorkerWire.REQUEST -> {
                            val method = fields.getString("method") ?: error("missing RPC method")
                            val params = fields.getString("params") ?: error("missing RPC params")
                            require(McpWorkerWire.isAllowedMethod(method) &&
                                McpWorkerWire.validRequestSize(params)) { "IPC request budget exceeded" }
                            val instance = instances[token] ?: throw IOException("MCP worker connection closed")
                            val result = instance.transport.request(
                                method, json.parseToJsonElement(params) as? JsonObject
                                    ?: error("MCP arguments must be an object"))
                            // The negotiating transport's terminal flow only exists *after*
                            // its first negotiated call. Starting earlier silently loses SSE
                            // deaths and can leave tools registered after a crash.
                            synchronized(instance) {
                                if (instance.terminalObserver == null) {
                                    instance.terminalObserver =
                                        (instance.transport as? McpTerminationSource)
                                            ?.terminalEvents?.let { events ->
                                                scope.launch {
                                                    events.collect { cause ->
                                                        invalidate(token)
                                                        send(sender, McpWorkerWire.TERMINATED, 0,
                                                            token, error = cause.javaClass.simpleName)
                                                    }
                                                }
                                            }
                                }
                            }
                            val encoded = json.encodeToString(JsonObject.serializer(), result)
                            require(McpWorkerWire.validResponseSize(encoded)) {
                                "MCP reply exceeds Binder budget; cannot declare success"
                            }
                            send(sender, McpWorkerWire.RESPONSE, requestId, token, result = encoded)
                        }
                        McpWorkerWire.CLOSE -> {
                            invalidate(token)
                            send(sender, McpWorkerWire.RESPONSE, requestId, token, result = "{}")
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    val transportFailure = failure is IOException
                    if (transportFailure) invalidate(token)
                    send(sender, McpWorkerWire.RESPONSE, requestId, token,
                        error = failure.javaClass.simpleName,
                        transportError = transportFailure,
                        httpStatus = (failure as? com.labteto.dshmobile.interop.mcp.McpHttpException)
                            ?.statusCode ?: 0)
                }
            }
        }
    })

    private fun invalidate(token: String) {
        instances.remove(token)?.let {
            it.terminalObserver?.cancel()
            runCatching { it.transport.close() }
        }
    }

    private fun send(to: Messenger, kind: Int, requestId: Int, token: String,
        result: String? = null, error: String? = null, transportError: Boolean = false,
        httpStatus: Int = 0) {
        val bytes = result?.toByteArray(Charsets.UTF_8)
        val pipe = if (bytes != null && bytes.size > McpWorkerWire.MAX_INLINE_RESPONSE_BYTES) {
            try { ParcelFileDescriptor.createPipe() }
            catch (_: java.io.IOException) {
                invalidate(token)
                return
            }
        } else null
        val msg = Message.obtain(null, kind, requestId, McpWorkerWire.VERSION).apply {
            data = Bundle().apply {
                putString("token", token)
                if (pipe == null) result?.let { putString("result", it) }
                else putParcelable("result_pipe", pipe[0])
                error?.let { putString("error", it.take(120)) }
                putBoolean("transport_error", transportError)
                if (httpStatus > 0) putInt("http_status", httpStatus)
            }
        }
        try {
            // Deliver read FD first; writer runs asynchronously to avoid blocking the
            // Binder thread on a full pipe before the consumer has received it.
            to.send(msg)
            if (pipe != null) {
                val writer = pipe[1]
                scope.launch {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                            output.write(bytes!!)
                        }
                    } catch (_: java.io.IOException) {
                        invalidate(token)
                    }
                }
            }
        } catch (_: android.os.RemoteException) {
            pipe?.get(1)?.close()
            invalidate(token)
        } finally {
            pipe?.get(0)?.close()
        }
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder
    override fun onDestroy() {
        instances.keys.toList().forEach(::invalidate)
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        super.onDestroy()
    }
}
