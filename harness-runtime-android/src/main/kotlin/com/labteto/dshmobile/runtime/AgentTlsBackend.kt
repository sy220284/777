package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.agent.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.io.DataOutputStream
import java.io.DataInputStream
import javax.net.ssl.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** TLS peer pin + explicit connector credential; no model/API credential enters this transport. */
class AgentTlsBackend(
    private val host: String, private val port: Int, private val peerSha256: String,
    private val credential: String,
    private val remoteTools: Boolean = false,
    private val requireGrantCurrent: suspend () -> Unit,
) : AgentTurnBackend {
    init {
        require(host.isNotBlank() && port in 1..65535 && peerSha256.matches(Regex("[0-9a-f]{64}")))
        require(credential.isNotBlank() && credential.toByteArray().size <= 65_535)
    }

    override suspend fun run(request: AgentBackendRequest, services: AgentBackendServices): AgentRunResult {
        requireGrantCurrent()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = error("client validation is server-owned")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val certificate = chain.firstOrNull() ?: error("后端未提供证书")
                certificate.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { "%02x".format(it) }
                check(MessageDigest.isEqual(actual.toByteArray(), peerSha256.toByteArray())) { "远端执行器证书已变化" }
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        val socket = ssl.socketFactory.createSocket() as SSLSocket
        val connecting = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var channel: FramedAgentChannel? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { socket.close() }
                connecting.launch {
                    try {
                        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        socket.connect(InetSocketAddress(host, port), 15_000)
                        socket.soTimeout = 15_000
                        socket.startHandshake()
                        DataOutputStream(socket.outputStream).apply { writeUTF(credential); flush() }
                        check(DataInputStream(socket.inputStream).readInt() == AgentBackendProtocol.VERSION) { "远端执行器未确认授权" }
                        socket.soTimeout = 90_000
                        continuation.resume(Unit)
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                }
            }
            requireGrantCurrent()
            val connection = FramedAgentChannel(socket.inputStream, socket.outputStream, onClose = socket::close)
            channel = connection
            val checked = AgentBackendServices(
                model = AgentModel { requireGrantCurrent(); services.model.complete(it) },
                tools = AgentToolExecutor { requireGrantCurrent(); services.tools.execute(it) },
                batch = AgentToolBatchExecutor { requireGrantCurrent(); services.batch.execute(it) },
                isParallel = services.isParallel,
                events = services.events,
                extend = services.extend,
            )
            return AgentBackendProtocol.host(connection, request.copy(remoteTools = remoteTools), checked, requireGrantCurrent)
        } finally { channel?.close(); socket.close(); connecting.cancel() }
    }
}
