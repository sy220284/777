package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.agent.AgentBackendRequest
import com.labteto.dshmobile.harness.agent.AgentBackendServices
import com.labteto.dshmobile.harness.agent.AgentEventSink
import com.labteto.dshmobile.harness.agent.AgentModel
import com.labteto.dshmobile.harness.agent.AgentModelReply
import com.labteto.dshmobile.harness.agent.AgentToolBatchExecutor
import com.labteto.dshmobile.harness.agent.AgentToolExecutor
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTlsBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Fixture(val server: AgentTlsWorkerServer, val port: Int, val pin: String)
    private fun fixture(): Fixture {
        val file = File(temporary.root, "peer-${System.nanoTime()}.p12")
        // Use the JDK already running the test, no downloaded TLS helper or external service.
        val generated = ProcessBuilder(File(System.getProperty("java.home"), "bin/keytool").path,
            "-genkeypair", "-alias", "peer", "-keystore", file.path, "-storetype", "PKCS12",
            "-storepass", "test-password", "-keypass", "test-password", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-noprompt")
            .redirectErrorStream(true).redirectOutput(File(temporary.root, "keytool.log")).start()
        check(generated.waitFor(20, TimeUnit.SECONDS) && generated.exitValue() == 0)
        val password = "test-password".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val listener = ssl.serverSocketFactory.createServerSocket(0, 8, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val pin = MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("peer").encoded)
            .joinToString("") { "%02x".format(it) }
        return Fixture(AgentTlsWorkerServer(listener, "connector-token", onConnectionFailure = { }), listener.localPort, pin)
    }
    private fun services(model: AgentModel) = AgentBackendServices(model,
        AgentToolExecutor { error("no tools") }, AgentToolBatchExecutor { error("no batch") },
        { false }, AgentEventSink { }, null)
    @Test fun pinnedAuthenticatedWorkerRunsTheCanonicalEngine() = runBlocking {
        val fixture = fixture()
        val server = launch(Dispatchers.IO) { fixture.server.serve() }
        try {
            val result = withTimeout(20_000) {
                AgentTlsBackend("localhost", fixture.port, fixture.pin, "connector-token") { }.run(
                    AgentBackendRequest("t", "task", emptyList(), 2), services(AgentModel { AgentModelReply("done") }))
            }
            assertEquals("done", result.answer)
        } finally { fixture.server.close(); server.cancelAndJoin() }
    }
    @Test fun wrongCertificateOrConnectorTokenCannotContactTheModel() = runBlocking {
        val fixture = fixture()
        val server = launch(Dispatchers.IO) { fixture.server.serve() }
        var modelCalls = 0
        try {
            for ((pin, token) in listOf("0".repeat(64) to "connector-token", fixture.pin to "wrong-token")) {
                val result = withTimeout(20_000) { runCatching {
                    AgentTlsBackend("localhost", fixture.port, pin, token) { }.run(
                        AgentBackendRequest("t", "private task", emptyList(), 2),
                        services(AgentModel { modelCalls++; AgentModelReply("should not run") }))
                } }
                assertTrue(result.isFailure)
            }
            assertEquals(0, modelCalls)
        } finally { fixture.server.close(); server.cancelAndJoin() }
    }
    @Test fun cancellationClosesTheSocketWhileModelWaits() = runBlocking {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val server = launch(Dispatchers.IO) { fixture.server.serve() }
        try {
            withTimeout(20_000) {
                val activation = async { AgentTlsBackend("localhost", fixture.port, fixture.pin, "connector-token") { }.run(
                    AgentBackendRequest("t", "task", emptyList(), 2),
                    services(AgentModel { entered.complete(Unit); awaitCancellation() })) }
                entered.await(); activation.cancelAndJoin()
                assertTrue(activation.isCancelled)
            }
        } finally { fixture.server.close(); server.cancelAndJoin() }
    }
}
