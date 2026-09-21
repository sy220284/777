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

/** Prepares the verified Python runtime bundled in the APK. */
@Singleton
class BundledPythonRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val runtimeHome = File(context.noBackupFilesDir, "runtime/python")
    val binDir: File = File(context.noBackupFilesDir, "runtime/bin")

    @Volatile
    private var activeHomeDir: File? = null

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
        require(version.isNotBlank()) { "内置 Python 版本信息为空" }

        val versionRoot = File(runtimeHome, "$version/$abi")
        val libraryDir = File(versionRoot, "lib")
        val homeDir = File(versionRoot, "home")
        val marker = File(versionRoot, ".ready")
        if (marker.readTextOrNull() != version) {
            versionRoot.deleteRecursively()
            libraryDir.mkdirs()
            homeDir.mkdirs()
            copyAssetDirectory("$ASSET_ROOT/$abi/lib", libraryDir)
            copyAssetDirectory("$ASSET_ROOT/$abi/home", homeDir)
            marker.writeText(version)
        }

        val nativePython = File(context.applicationInfo.nativeLibraryDir, NATIVE_PYTHON_NAME)
        require(nativePython.isFile && nativePython.canExecute()) {
            "内置 Python 可执行文件未从 APK 提取：${nativePython.path}"
        }

        binDir.mkdirs()
        PYTHON_COMMANDS.forEach { name ->
            val link = File(binDir, name).toPath()
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, nativePython.toPath())
        }

        runtimeHome.listFiles()
            ?.filter { it.isDirectory && it.name != version }
            ?.forEach { it.deleteRecursively() }

        activeHomeDir = homeDir
        activeLibraryDir = libraryDir
        runtimeVersion = version
        prepared = true
    }

    fun searchPaths(): List<File> = if (prepared) listOf(binDir) else emptyList()

    fun environment(): Map<String, String> {
        val homeDir = activeHomeDir ?: return emptyMap()
        val libraryDir = activeLibraryDir ?: return emptyMap()
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val stdlib = File(homeDir, "lib/python$PYTHON_MAJOR_MINOR")
        return mapOf(
            "LD_LIBRARY_PATH" to listOf(libraryDir.path, nativeDir).joinToString(File.pathSeparator),
            "PYTHONHOME" to homeDir.path,
            "PYTHONPATH" to listOf(stdlib.path, File(stdlib, "lib-dynload").path)
                .joinToString(File.pathSeparator),
            "PYTHONDONTWRITEBYTECODE" to "1",
            "PYTHONNOUSERSITE" to "1",
            "HOME" to context.filesDir.path,
            "TMPDIR" to context.cacheDir.path,
        )
    }

    fun status(): String = when {
        prepared -> "Python ${runtimeVersion.orEmpty()} 已就绪"
        Build.SUPPORTED_ABIS.none(SUPPORTED_ABIS::contains) ->
            "当前 ABI 不支持内置 Python：${Build.SUPPORTED_ABIS.joinToString()}"
        else -> "Python 尚未完成初始化"
    }

    private fun copyAssetDirectory(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        require(children.isNotEmpty()) { "内置 Python 运行时目录为空：$assetPath" }
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
        const val ASSET_ROOT = "runtime/python"
        const val VERSION_ASSET = "$ASSET_ROOT/python-version.txt"
        const val NATIVE_PYTHON_NAME = "libdsh_python.so"
        const val PYTHON_MAJOR_MINOR = "3.14"
        val PYTHON_COMMANDS = listOf("python", "python3", "python3.14")
        val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
    }
}
