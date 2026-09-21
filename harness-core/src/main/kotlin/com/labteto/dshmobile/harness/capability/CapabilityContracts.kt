package com.labteto.dshmobile.harness.capability

data class ProcessRequest(
    val command: List<String>,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    val timeoutMillis: Long = 30_000L,
)

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
    val timedOut: Boolean = false,
    val signal: String? = null,
)

interface HarnessFileSystem {
    fun read(path: String): String
    fun write(path: String, content: String)
    fun exists(path: String): Boolean
    fun list(path: String = "."): List<String>
}

interface HarnessProcessRuntime {
    suspend fun execute(request: ProcessRequest): ProcessResult
    fun isCommandAvailable(command: String): Boolean
}

interface HarnessTerminalProvider {
    suspend fun open(command: List<String>, workingDirectory: String? = null): String
    suspend fun write(sessionId: String, input: String)
    suspend fun read(sessionId: String): String
    suspend fun close(sessionId: String)
}

interface HarnessNetworkProvider {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray
}

interface HarnessCredentialStore {
    suspend fun put(key: String, secret: String)
    suspend fun get(key: String): String?
    suspend fun delete(key: String)
}

interface HarnessDeviceProvider {
    val capabilities: Set<String>
    suspend fun invoke(capability: String, arguments: Map<String, String>): String
}

interface HarnessLlmProvider {
    suspend fun complete(model: String, payload: String): String
}

interface HarnessScheduler {
    suspend fun schedule(id: String, triggerAtMillis: Long, payload: String)
    suspend fun cancel(id: String)
}
