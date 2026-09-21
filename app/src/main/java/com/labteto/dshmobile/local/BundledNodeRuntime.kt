package com.labteto.dshmobile.local

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    suspend fun prepare() = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull(SUPPORTED_ABIS::contains)
            ?: return@withContext
        val version = context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        require(version.isNotBlank()) { "内置 Node 版本信息为空" }

        val versionRoot = File(runtimeHome, "$version/$abi")
        val libraryDir = File(versionRoot, "lib")
        val marker = File(versionRoot, ".ready")
        if (marker.readTextOrNull() != version) {
            versionRoot.deleteRecursively()
            libraryDir.mkdirs()
            copyAssetDirectory("$ASSET_ROOT/$abi/lib", libraryDir)
            marker.writeText(version)
        }

        val nativeNode = File(context.applicationInfo.nativeLibraryDir, NATIVE_NODE_NAME)
        require(nativeNode.isFile && nativeNode.canExecute()) {
            "内置 Node 可执行文件未从 APK 提取：${nativeNode.path}"
        }

        binDir.mkdirs()
        val nodeLink = File(binDir, "node").toPath()
        Files.deleteIfExists(nodeLink)
        Files.createSymbolicLink(nodeLink, nativeNode.toPath())

        runtimeHome.listFiles()
            ?.filter { it.isDirectory && it.name != version }
            ?.forEach { it.deleteRecursively() }

        activeLibraryDir = libraryDir
        runtimeVersion = version
        prepared = true
    }

    fun searchPaths(): List<File> = if (prepared) listOf(binDir) else emptyList()

    fun environment(): Map<String, String> {
        val libraryDir = activeLibraryDir ?: return emptyMap()
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return mapOf(
            "LD_LIBRARY_PATH" to listOf(libraryDir.path, nativeDir).joinToString(File.pathSeparator),
            "HOME" to context.filesDir.path,
            "TMPDIR" to context.cacheDir.path,
        )
    }

    fun status(): String = when {
        prepared -> "Node ${runtimeVersion.orEmpty()} 已就绪"
        Build.SUPPORTED_ABIS.none(SUPPORTED_ABIS::contains) ->
            "当前 ABI 不支持内置 Node：${Build.SUPPORTED_ABIS.joinToString()}"
        else -> "Node 尚未完成初始化"
    }

    private fun copyAssetDirectory(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        require(children.isNotEmpty()) { "内置 Node 运行库目录为空：$assetPath" }
        children.forEach { name ->
            val childAsset = "$assetPath/$name"
            val nested = context.assets.list(childAsset).orEmpty()
            val destination = File(target, name)
            if (nested.isNotEmpty()) {
                destination.mkdirs()
                copyAssetDirectory(childAsset, destination)
            } else {
                destination.parentFile?.mkdirs()
                context.assets.open(childAsset).use { input ->
                    destination.outputStream().use(input::copyTo)
                }
                destination.setReadable(true, true)
                destination.setWritable(true, true)
                destination.setExecutable(false, false)
            }
        }
    }

    private fun File.readTextOrNull(): String? =
        runCatching { takeIf(File::isFile)?.readText()?.trim() }.getOrNull()

    private companion object {
        const val ASSET_ROOT = "runtime/node"
        const val VERSION_ASSET = "$ASSET_ROOT/node-version.txt"
        const val NATIVE_NODE_NAME = "libdsh_node.so"
        val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
    }
}
