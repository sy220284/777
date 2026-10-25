package com.labteto.dshmobile.runtime

import java.io.FileInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** Self-hosted JVM entry point; use the existing Gradle application distribution, no cloud dependency. */
suspend fun main(args: Array<String>) {
    val environment = System.getenv()
    val keystore = requireNotNull(environment["DSH_AGENT_KEYSTORE"]) { "DSH_AGENT_KEYSTORE required" }
    val password = requireNotNull(environment["DSH_AGENT_KEYSTORE_PASSWORD"]).toCharArray()
    val credential = requireNotNull(environment["DSH_AGENT_TOKEN"]) { "DSH_AGENT_TOKEN required" }
    require(credential.isNotBlank())
    val keys = KeyStore.getInstance("PKCS12").apply { FileInputStream(keystore).use { load(it, password) } }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
    val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    val port = environment["DSH_AGENT_PORT"]?.toInt() ?: 8443
    val address = InetAddress.getByName(environment["DSH_AGENT_BIND"] ?: "127.0.0.1")
    val socket = context.serverSocketFactory.createServerSocket(port, 50, address) as SSLServerSocket
    val server = AgentTlsWorkerServer(socket, credential, environment["DSH_AGENT_MAX_ACTIVATIONS"]?.toInt() ?: 8)
    val identity = keys.aliases().toList().filter { keys.isKeyEntry(it) }.single()
    val pin = MessageDigest.getInstance("SHA-256").digest(keys.getCertificateChain(identity).first().encoded)
        .joinToString("") { "%02x".format(it) }
    println("DSH_AGENT_CERT_SHA256=$pin")
    println("DSH_AGENT_LISTEN=${socket.inetAddress.hostAddress}:${socket.localPort}")
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    try { server.serve() } finally { server.close(); password.fill('\u0000') }
}
