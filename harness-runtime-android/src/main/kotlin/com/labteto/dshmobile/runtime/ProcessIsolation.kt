package com.labteto.dshmobile.runtime

import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only the backend admission owner can install this immutable process restriction. */
class ProcessIsolationContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProcessIsolationContext>
}

/**
 * /proc/self and /proc/thread-self are process-relative kernel links, not
 * ordinary filesystem aliases. Canonicalizing them in the Android app would
 * freeze the app's PID/TID into a Landlock policy used later by another child.
 * Preserve the links so Landlock resolves them in the sandbox launcher itself.
 */
internal fun isolationRootPath(file: File): String = when (file.path) {
    "/proc/self", "/proc/thread-self" -> file.path
    else -> file.canonicalPath
}

/**
 * A sandbox can only write beneath its admitted workspace. The shared app-wide
 * npm/pip caches are valid for trusted local tools, but must not leak into an
 * isolated Work/Agent command. Use one workspace-scoped location for every
 * package manager entry point (Process, Shell, PTY and LSP).
 */
internal fun isolatedChildRuntimeEnvironment(workspace: File, original: Map<String, String>): Map<String, String> {
    val scopedRoot = File(workspace.canonicalFile, ".dsh/runtime-packages")
    return original.toMutableMap().apply {
        if (containsKey("PIP_TARGET")) {
            val packages = File(scopedRoot, "python").path
            put("PIP_TARGET", packages)
            put("PIP_CACHE_DIR", File(scopedRoot, "pip-cache").path)
            put("PYTHONPATH", (listOf(packages) +
                get("PYTHONPATH").orEmpty().split(File.pathSeparatorChar))
                .filter(String::isNotBlank).distinct().joinToString(File.pathSeparator))
        }
        if (containsKey("npm_config_cache")) {
            put("npm_config_cache", File(scopedRoot, "npm-cache").path)
            put("npm_config_prefix", File(scopedRoot, "npm-global").path)
        }
    }
}

/** Wrap only the untrusted child, preserving the host's PTY/process-group/receipt ownership. */
private suspend fun isolatedProcessCommand(
    command: List<String>, directory: File?, trustedPaths: List<File>, trustedEnvironment: Map<String, String>,
    trustedReadRoots: List<File>,
): List<String> {
    if (currentCoroutineContext()[ProcessIsolationContext] == null) return command
    val workspace = requireNotNull(directory) { "隔离命令缺少固定工作目录" }.canonicalFile
    val python = trustedPaths.asSequence().map { File(it, "python3") }
        .firstOrNull { it.isFile && it.canExecute() } ?: error("PROCESS_ISOLATION_PYTHON_UNAVAILABLE")
    val script = requireNotNull(ProcessIsolationContext::class.java.getResourceAsStream(
        "/com/labteto/dshmobile/runtime/process_sandbox.py")) { "PROCESS_ISOLATION_HELPER_MISSING" }
        .bufferedReader().use { it.readText() }
    val runtimeKeys = setOf("LD_LIBRARY_PATH", "PYTHONHOME", "PYTHONPATH", "GIT_EXEC_PATH", "GIT_TEMPLATE_DIR",
        "SSL_CERT_FILE", "OPENSSL_CONF", "NODE_EXTRA_CA_CERTS", "DSH_NPM_HOME")
    // Android's native linker needs its configuration and Node/V8 must inspect
    // its own process metadata. Never grant the entire /proc tree: sibling
    // process state and Binder endpoints remain excluded.
    val roots = (listOf("/system", "/apex", "/vendor", "/product", "/system_ext",
        "/linkerconfig", "/proc/self", "/proc/thread-self", "/proc/cpuinfo", "/proc/meminfo",
        // V8's random initialization needs the standard random device; omitting
        // it can trigger Android's denied legacy sysctl(156) fallback (SIGSYS).
        // Admit only standard device nodes, never the whole /dev directory.
        "/dev/null", "/dev/urandom", "/dev/random", "/dev/zero", "/dev/tty") +
        trustedReadRoots.map { it.canonicalPath } + trustedPaths.map { it.canonicalPath } + trustedEnvironment.filterKeys { it in runtimeKeys }.values
            .flatMap { it.split(File.pathSeparatorChar) }).map(::File).filter(File::exists).map(::isolationRootPath).distinct()
    val policy = buildJsonObject {
        put("read", JsonArray(roots.map(::JsonPrimitive)))
        put("write", JsonArray(listOf(JsonPrimitive(workspace.path))))
    }
    return listOf(python.path, "-P", "-s", "-u", "-c", script, policy.toString(), JsonArray(command.map(::JsonPrimitive)).toString())
}

/** Interpreter startup is trusted too: model-provided LD_PRELOAD/PYTHONPATH cannot run before seccomp. */
suspend fun secureIsolatedProcessBuilder(
    builder: ProcessBuilder, trustedPaths: List<File>, trustedEnvironment: Map<String, String>,
    trustedReadRoots: List<File> = emptyList(),
): ProcessBuilder {
    if (currentCoroutineContext()[ProcessIsolationContext] == null) return builder
    val directory = requireNotNull(builder.directory()) { "隔离命令缺少工作目录" }.canonicalFile
    val childEnvironment = isolatedChildRuntimeEnvironment(directory, builder.environment().toMap())
    val wrapped = isolatedProcessCommand(builder.command(), directory, trustedPaths, trustedEnvironment, trustedReadRoots)
    // argv is visible in process listings. Pass only environment KEY names in argv;
    // values travel through inert, indexed variables that the Python bootstrap
    // restores only after installing both Landlock and Seccomp.
    val childKeys = childEnvironment.keys.sorted()
    builder.command(wrapped + JsonArray(childKeys.map(::JsonPrimitive)).toString())
    val platformKeys = setOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "TZ")
    builder.environment().apply {
        clear()
        putAll(System.getenv().filterKeys { it in platformKeys })
        putAll(trustedEnvironment)
        put("PATH", trustedPaths.joinToString(File.pathSeparator) { it.path })
        put("PYTHONNOUSERSITE", "1"); put("PYTHONDONTWRITEBYTECODE", "1")
        childKeys.forEachIndexed { index, key ->
            val staging = "DSH_SANDBOX_ENV_$index"
            require(!containsKey(staging)) { "隔离环境暂存键冲突：$staging" }
            put(staging, childEnvironment.getValue(key))
        }
    }
    return builder
}
