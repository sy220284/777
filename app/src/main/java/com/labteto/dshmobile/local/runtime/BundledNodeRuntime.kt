package com.labteto.dshmobile.local.runtime

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prepares the Node runtime that ships inside the APK.
 *
 * Android 10+ denies execve from writable app data for targetSdk 29+, so the executable itself
 * remains in nativeLibraryDir. Only its shared-library dependencies are copied from APK assets.
 * The writable "node" entry is a symlink whose resolved inode still lives in nativeLibraryDir.
 */
@Singleton
class BundledNodeRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val runtimeHome = File(context.noBackupFilesDir, "runtime/node")
    val binDir: File = File(context.noBackupFilesDir, "runtime/bin")

    @Volatile
    private var activeLibraryDir: File? = null

    @Volatile
    private var runtimeVersion: String? = null

    @Volatile
    private var prepared = false

    @Volatile
    private var platformCaBundle: File? = null

    suspend fun prepare() = withContext(Dispatchers.IO) {
        // Multiple runtime instances may be created by startup, instrumentation and recovery.
        // Serialize the entire prepare/cleanup/link sequence, not only blob extraction.
        prepareMutex.withLock {
            val abi = Build.SUPPORTED_ABIS.firstOrNull(SUPPORTED_ABIS::contains)
                ?: return@withLock
            val version = context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
            require(version.isNotBlank()) { "内置 Node 版本信息为空" }

            val versionRoot = File(runtimeHome, "$version/$abi")
            val libraryDir = File(versionRoot, "lib")
            BundledRuntimeInstallation.prepareVersion(context, "node", versionRoot, version, abi)

            val nativeNode = File(context.applicationInfo.nativeLibraryDir, NATIVE_NODE_NAME)
            require(nativeNode.isFile && nativeNode.canExecute()) {
                "内置 Node 可执行文件未从 APK 提取：${nativeNode.path}"
            }

            BundledRuntimeInstallation.linkExecutable(nativeNode, File(binDir, "node"))

            BundledRuntimeInstallation.removeOldVersions(runtimeHome, version)

            // Node from the embedded Termux runtime cannot assume that a Termux filesystem
            // or its OpenSSL CA directory exists inside this Android application.
            // Export only roots the Android platform already trusts. Never disable TLS checks.
            val caBundle = installPlatformCaBundle()
            activeLibraryDir = libraryDir
            platformCaBundle = caBundle
            runtimeVersion = version
            prepared = true
        }
    }

    fun searchPaths(): List<File> = if (prepared) listOf(binDir) else emptyList()

    fun environment(): Map<String, String> {
        val libraryDir = activeLibraryDir ?: return emptyMap()
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return mapOf(
            "LD_LIBRARY_PATH" to listOf(libraryDir.path, nativeDir).joinToString(File.pathSeparator),
            "HOME" to context.filesDir.path,
            "TMPDIR" to context.cacheDir.path,
            "NODE_EXTRA_CA_CERTS" to checkNotNull(platformCaBundle).absolutePath,
        )
    }

    private fun installPlatformCaBundle(): File {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val certificates = factory.trustManagers.filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }
        check(certificates.isNotEmpty()) { "Android 系统未提供可信根证书，无法安全启用 Node HTTPS。" }
        val pem = buildString {
            certificates.map { it.encoded }.distinctBy { Base64.getEncoder().encodeToString(it) }
                .forEach { encoded ->
                    append("-----BEGIN CERTIFICATE-----\n")
                    append(Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.US_ASCII))
                        .encodeToString(encoded))
                    append("\n-----END CERTIFICATE-----\n")
                }
        }
        val directory = File(context.noBackupFilesDir, "runtime/tls")
        check(directory.isDirectory || directory.mkdirs()) { "Node 证书目录创建失败" }
        val target = File(directory, "android-trusted-roots.pem")
        if (target.isFile && target.readText() == pem) return target
        val stage = File(directory, ".android-trusted-roots.pem.tmp")
        try {
            stage.outputStream().use { stream ->
                stream.write(pem.toByteArray(Charsets.US_ASCII))
                stream.flush()
                stream.fd.sync()
            }
            check(stage.setReadable(false, false) && stage.setReadable(true, true) &&
                stage.setWritable(false, false) && stage.setWritable(true, true)) {
                "Node 证书文件权限设置失败"
            }
            java.nio.file.Files.move(stage.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally {
            stage.delete()
        }
        return target
    }

    fun status(): String = when {
        prepared -> "Node ${runtimeVersion.orEmpty()} 已就绪"
        Build.SUPPORTED_ABIS.none(SUPPORTED_ABIS::contains) ->
            "当前 ABI 不支持内置 Node：${Build.SUPPORTED_ABIS.joinToString()}"
        else -> "Node 尚未完成初始化"
    }

    private companion object {
        private val prepareMutex = Mutex()
        const val ASSET_ROOT = "runtime/node"
        const val VERSION_ASSET = "$ASSET_ROOT/node-version.txt"
        const val NATIVE_NODE_NAME = "libdsh_node.so"
        val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
    }
}
