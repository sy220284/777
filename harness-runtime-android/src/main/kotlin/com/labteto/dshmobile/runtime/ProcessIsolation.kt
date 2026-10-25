package com.labteto.dshmobile.runtime

import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only the backend admission owner can install this immutable process restriction. */
class ProcessIsolationContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProcessIsolationContext>
}

/** Wrap only the untrusted child, preserving the host's PTY/process-group/receipt ownership. */
private suspend fun isolatedProcessCommand(
    command: List<String>, directory: File?, trustedPaths: List<File>, trustedEnvironment: Map<String, String>,
): List<String> {
    if (currentCoroutineContext()[ProcessIsolationContext] == null) return command
    val workspace = requireNotNull(directory) { "隔离命令缺少固定工作目录" }.canonicalFile
    val python = trustedPaths.asSequence().map { File(it, "python3") }
        .firstOrNull { it.isFile && it.canExecute() } ?: error("PROCESS_ISOLATION_PYTHON_UNAVAILABLE")
    val script = requireNotNull(ProcessIsolationContext::class.java.getResourceAsStream(
        "/com/labteto/dshmobile/runtime/process_sandbox.py")) { "PROCESS_ISOLATION_HELPER_MISSING" }
        .bufferedReader().use { it.readText() }
    val runtimeKeys = setOf("LD_LIBRARY_PATH", "PYTHONHOME", "PYTHONPATH", "GIT_EXEC_PATH", "GIT_TEMPLATE_DIR",
        "SSL_CERT_FILE", "OPENSSL_CONF", "NODE_EXTRA_CA_CERTS")
    val roots = (listOf("/system", "/apex", "/vendor", "/product", "/system_ext", "/dev/null", "/dev/urandom", "/dev/tty") +
        trustedPaths.map { it.canonicalPath } + trustedEnvironment.filterKeys { it in runtimeKeys }.values
            .flatMap { it.split(File.pathSeparatorChar) }).map(::File).filter(File::exists).map { it.canonicalPath }.distinct()
    val policy = buildJsonObject {
        put("read", JsonArray(roots.map(::JsonPrimitive)))
        put("write", JsonArray(listOf(JsonPrimitive(workspace.path))))
    }
    return listOf(python.path, "-s", "-u", "-c", script, policy.toString(), JsonArray(command.map(::JsonPrimitive)).toString())
}

/** Interpreter startup is trusted too: model-provided LD_PRELOAD/PYTHONPATH cannot run before seccomp. */
suspend fun secureIsolatedProcessBuilder(
    builder: ProcessBuilder, trustedPaths: List<File>, trustedEnvironment: Map<String, String>,
): ProcessBuilder {
    if (currentCoroutineContext()[ProcessIsolationContext] == null) return builder
    val childEnvironment = JsonObject(builder.environment().mapValues { JsonPrimitive(it.value) }).toString()
    val wrapped = isolatedProcessCommand(builder.command(), builder.directory(), trustedPaths, trustedEnvironment)
    builder.command(wrapped + childEnvironment)
    val platformKeys = setOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "TZ")
    builder.environment().apply {
        clear()
        putAll(System.getenv().filterKeys { it in platformKeys })
        putAll(trustedEnvironment)
        put("PATH", trustedPaths.joinToString(File.pathSeparator) { it.path })
        put("PYTHONNOUSERSITE", "1"); put("PYTHONDONTWRITEBYTECODE", "1")
    }
    return builder
}
