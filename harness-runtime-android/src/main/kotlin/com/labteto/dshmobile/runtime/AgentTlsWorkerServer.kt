package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.agent.AgentBackendProtocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore

/** Deployable worker endpoint. Client disappearance/lease expiry closes the activation, never replays it. */
class AgentTlsWorkerServer(
    private val listener: SSLServerSocket,
    private val connectorCredential: String,
    maxActivations: Int = 8,
    private val environmentFactory: (suspend (com.labteto.dshmobile.harness.agent.AgentBackendRequest) -> com.labteto.dshmobile.harness.agent.AgentEnvironmentTools)? = null,
    private val onConnectionFailure: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {
    private val permits = Semaphore(maxActivations)
    private val connections = ConcurrentHashMap.newKeySet<SSLSocket>()

    // FEATURE_TREE: {"id":"runtime.agent-remote.environment","path":"第三层｜平台与工程保障 / 代理执行后端 / 受审批的远端文件与原生进程执行（PR #874，完整环境仍待补齐）","entry":"serve","owner":"TOOLS","test":"harness-runtime-android/src/test/kotlin/com/labteto/dshmobile/runtime/AgentTlsBackendTest.kt#tlsToolEffectWritesOnlyTheServerWorkspaceAfterHostApproval"}
    suspend fun serve(): Unit = coroutineScope {
        while (isActive && !listener.isClosed) {
            permits.acquire()
            val socket = try { withContext(Dispatchers.IO) { listener.accept() as SSLSocket } }
            catch (error: Exception) { permits.release(); if (listener.isClosed) break else throw error }
            connections.add(socket)
            launch(Dispatchers.IO) {
                try {
                    socket.soTimeout = 15_000
                    socket.startHandshake()
                    val supplied = DataInputStream(socket.inputStream).readUTF()
                    check(MessageDigest.isEqual(supplied.toByteArray(), connectorCredential.toByteArray())) { "AGENT_BACKEND_AUTHENTICATION_FAILED" }
                    DataOutputStream(socket.outputStream).apply { writeInt(AgentBackendProtocol.VERSION); flush() }
                    socket.soTimeout = 90_000 // Active host heartbeats keep approval/long-tool waits alive.
                    val channel = FramedAgentChannel(socket.inputStream, socket.outputStream, onClose = socket::close)
                    try { AgentBackendProtocol.worker(channel, environmentFactory) } finally { channel.close() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    onConnectionFailure("AGENT_BACKEND_CONNECTION_CLOSED: ${failure.javaClass.simpleName}")
                    // Per-connection failure terminates this worker only. The host's original
                    // receipt ledger determines whether an already-started effect needs inspection.
                } finally { connections.remove(socket); socket.close(); permits.release() }
            }
        }
    }

    override fun close() { listener.close(); connections.forEach { it.close() } }
}
