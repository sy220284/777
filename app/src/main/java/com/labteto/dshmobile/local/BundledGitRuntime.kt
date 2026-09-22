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

/** Prepares the verified Git runtime bundled in the APK. */
@Singleton
class BundledGitRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val runtimeHome = File(context.noBackupFilesDir, "runtime/git")
    val binDir: File = File(context.noBackupFilesDir, "runtime/bin")

    @Volatile
    private var activeLibraryDir: File? = null

    @Volatile
    private var activeHelperDir: File? = null

    @Volatile
    private var activeTemplateDir: File? = null

    @Volatile
    private var activeCertFile: File? = null

    @Volatile
    private var runtimeVersion: String? = null

    @Volatile
    private var prepared = false

    suspend fun prepare() = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull(SUPPORTED_ABIS::contains)
            ?: return@withContext
        val version = context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        require(version.isNotBlank()) { "内置 Git 版本信息为空" }

        val versionRoot = File(runtimeHome, "$version/$abi")
        val libraryDir = File(versionRoot, "lib")
        val homeDir = File(versionRoot, "home")
        val helperDir = File(versionRoot, "libexec/git-core")
        val marker = File(versionRoot, ".ready")
        if (marker.readTextOrNull() != version) {
            versionRoot.deleteRecursively()
            libraryDir.mkdirs()
            homeDir.mkdirs()
            helperDir.mkdirs()
            copyAssetDirectory("$ASSET_ROOT/$abi/lib", libraryDir)
            copyAssetDirectory("$ASSET_ROOT/$abi/home", homeDir)
            marker.writeText(version)
        } else {
            helperDir.mkdirs()
        }

        val nativeGit = File(context.applicationInfo.nativeLibraryDir, NATIVE_GIT_NAME)
        require(nativeGit.isFile && nativeGit.canExecute()) {
            "内置 Git 可执行文件未从 APK 提取：${nativeGit.path}"
        }

        binDir.mkdirs()
        val gitLink = File(binDir, "git").toPath()
        Files.deleteIfExists(gitLink)
        Files.createSymbolicLink(gitLink, nativeGit.toPath())

        helperDir.listFiles()?.forEach { it.delete() }
        val helpers = context.assets.open("$ASSET_ROOT/$abi/helpers.tsv")
            .bufferedReader()
            .useLines { lines ->
                lines.map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { line ->
                        val fields = line.split('\t')
                        require(fields.size == 2) { "Git helper 清单格式无效：$line" }
                        fields[0] to fields[1]
                    }
                    .toList()
            }
        require(helpers.any { it.first == "git-remote-http" }) { "缺少 git-remote-http helper" }
        require(helpers.any { it.first == "git-remote-https" }) { "缺少 git-remote-https helper" }
        helpers.forEach { (command, nativeName) ->
            val nativeHelper = File(context.applicationInfo.nativeLibraryDir, nativeName)
            require(nativeHelper.isFile && nativeHelper.canExecute()) {
                "内置 Git helper 未从 APK 提取：$nativeName"
            }
            val link = File(helperDir, command).toPath()
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, nativeHelper.toPath())
        }

        val templateDir = File(homeDir, "share/git-core/templates")
        require(templateDir.isDirectory) { "内置 Git 模板目录未打包" }
        val certFile = File(homeDir, "etc/tls/cert.pem")
        require(certFile.isFile) { "内置 Git CA 证书未打包" }

        runtimeHome.listFiles()
            ?.filter { it.isDirectory && it.name != version }
            ?.forEach { it.deleteRecursively() }

        activeLibraryDir = libraryDir
        activeHelperDir = helperDir
        activeTemplateDir = templateDir
        activeCertFile = certFile
        runtimeVersion = version
        prepared = true
    }

    fun searchPaths(): List<File> = if (prepared) listOf(binDir) else emptyList()

    fun environment(): Map<String, String> {
        val libraryDir = activeLibraryDir ?: return emptyMap()
        val helperDir = activeHelperDir ?: return emptyMap()
        val templateDir = activeTemplateDir ?: return emptyMap()
        val certFile = activeCertFile ?: return emptyMap()
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return mapOf(
            "LD_LIBRARY_PATH" to listOf(libraryDir.path, nativeDir).joinToString(File.pathSeparator),
            "GIT_EXEC_PATH" to helperDir.path,
            "GIT_TEMPLATE_DIR" to templateDir.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_ATTR_NOSYSTEM" to "1",
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_PAGER" to "cat",
            "PAGER" to "cat",
            "GIT_EDITOR" to "/system/bin/true",
            "EDITOR" to "/system/bin/true",
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "core.hooksPath",
            "GIT_CONFIG_VALUE_0" to "/dev/null",
            "GIT_SSL_CAINFO" to certFile.path,
            "SSL_CERT_FILE" to certFile.path,
            "HOME" to context.filesDir.path,
            "TMPDIR" to context.cacheDir.path,
            "LC_ALL" to "C",
        )
    }

    fun status(): String = when {
        prepared -> "Git ${runtimeVersion.orEmpty()} 已就绪"
        Build.SUPPORTED_ABIS.none(SUPPORTED_ABIS::contains) ->
            "当前 ABI 不支持内置 Git：${Build.SUPPORTED_ABIS.joinToString()}"
        else -> "Git 尚未完成初始化"
    }

    private fun copyAssetDirectory(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        require(children.isNotEmpty()) { "内置 Git 运行时目录为空：$assetPath" }
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
        const val ASSET_ROOT = "runtime/git"
        const val VERSION_ASSET = "$ASSET_ROOT/git-version.txt"
        const val NATIVE_GIT_NAME = "libdsh_git.so"
        val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
    }
}
