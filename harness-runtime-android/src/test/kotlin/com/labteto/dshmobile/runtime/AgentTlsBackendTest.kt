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
    private fun fixture(remoteRoot: File? = null): Fixture {
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
        val environmentFactory: (suspend (AgentBackendRequest) -> com.labteto.dshmobile.harness.agent.AgentEnvironmentTools)? =
            if (remoteRoot == null) null else { _ -> AgentWorkspaceEnvironment.create(remoteRoot) }
        return Fixture(AgentTlsWorkerServer(listener, "connector-token", environmentFactory = environmentFactory,
            onConnectionFailure = { }), listener.localPort, pin)
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
    @Test fun remoteWorkspaceOptInDoesNotFallBackToHostWhenServerHasNoEnvironment() = runBlocking {
        val fixture = fixture()
        val server = launch(Dispatchers.IO) { fixture.server.serve() }
        var modelCalls = 0
        try {
            val result = withTimeout(20_000) { runCatching {
                AgentTlsBackend("localhost", fixture.port, fixture.pin, "connector-token", remoteTools = true) { }.run(
                    AgentBackendRequest("t", "private task", emptyList(), 1),
                    services(AgentModel { modelCalls++; AgentModelReply("must not run") }))
            } }
            assertTrue(result.isFailure)
            assertEquals(0, modelCalls)
        } finally { fixture.server.close(); server.cancelAndJoin() }
    }

    @Test fun tlsToolEffectWritesOnlyTheServerWorkspaceAfterHostApproval() = runBlocking {
        val root = temporary.newFolder("remote-root")
        val fixture = fixture(root)
        val server = launch(Dispatchers.IO) { fixture.server.serve() }
        val registry = com.labteto.dshmobile.harness.tools.ToolRegistry()
        var approvals = 0
        registry.register(com.labteto.dshmobile.harness.tools.HarnessTool("write",
            com.labteto.dshmobile.harness.tools.functionToolSchema("write", "write", parameterSchema =
                kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("object")) }),
            com.labteto.dshmobile.harness.tools.ToolAccess.WORKSPACE_WRITE,
            com.labteto.dshmobile.harness.tools.ToolApprovalPolicy.ALWAYS,
            com.labteto.dshmobile.harness.tools.ToolExposure.CORE,
            com.labteto.dshmobile.harness.tools.ToolMetadata("files", environmentBound = true),
            executor = com.labteto.dshmobile.harness.tools.HarnessToolExecutor { _, _, _ -> error("host effect forbidden") }))
        val input = kotlinx.serialization.json.buildJsonObject {
            put("path", kotlinx.serialization.json.JsonPrimitive("result.txt"))
            put("content", kotlinx.serialization.json.JsonPrimitive("actual remote effect"))
        }
        val call = com.labteto.dshmobile.harness.agent.AgentToolCall("write-call", "write", input)
        try {
            val result = withTimeout(20_000) {
                AgentTlsBackend("localhost", fixture.port, fixture.pin, "connector-token", remoteTools = true) { }.run(
                    AgentBackendRequest("t", "task", emptyList(), 1), AgentBackendServices(
                        AgentModel { AgentModelReply(toolCalls = listOf(call)) }, AgentToolExecutor {
                            kotlinx.coroutines.withContext(com.labteto.dshmobile.harness.tools.ToolInvocationContext("s", it.id, "receipt")) {
                                val receipt = registry.executeTracked(it.name, it.arguments, context =
                                    com.labteto.dshmobile.harness.tools.ToolContext(sessionId = "s", approval = { approvals++; true }))
                                assertTrue(receipt.executionStarted)
                                com.labteto.dshmobile.harness.agent.AgentToolResult(receipt.result.content, executionId = "receipt")
                            }
                        }, AgentToolBatchExecutor { error("no batch") }, { false }, AgentEventSink { }, null))
            }
            assertEquals(1, approvals)
            assertEquals("actual remote effect", File(root, "result.txt").readText())
            assertTrue(result.messages.last().content.contains("远端"))
        } finally { fixture.server.close(); server.cancelAndJoin() }
    }

}
