package com.labteto.dshmobile.local

import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Installs JavaScript-based LSP servers only when a semantic query needs one.
 * Nothing is downloaded at startup or when the Agent merely lists capabilities.
 *
 * The installer never launches downloaded native executables: npm-cli and each LSP
 * entry point are JS files interpreted by the APK's own Node executable. All npm
 * lifecycle scripts and optional native dependencies are disabled.
 */
internal class OnDemandLanguageServerManager(
    private val home: File,
    private val commandAvailable: (String) -> Boolean,
    private val resolveCommand: (List<String>) -> List<String>,
    private val processEnvironment: () -> Map<String, String>,
    private val fetchArchive: (String, File) -> Unit = ::downloadArchive,
) {
    internal enum class Component(val id: String, val version: String, val entry: String) {
        TYPESCRIPT("typescript", "6.0.1", "node_modules/typescript-language-server/lib/cli.mjs"),
        PYRIGHT("pyright", "1.1.410", "node_modules/pyright/langserver.index.js"),
    }

    private val installationMutex = Mutex()

    internal fun processReadRoots(): List<File> = Component.entries.map(::installedDirectory)
        .filter(File::isDirectory)

    internal fun componentFor(path: String?): Component? {
        val extension = path?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase()
        return when (extension) {
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts" -> Component.TYPESCRIPT
            "py", "pyw" -> Component.PYRIGHT
            else -> null
        }
    }

    internal fun installedCommand(path: String?): List<String> {
        val component = componentFor(path) ?: return emptyList()
        if (!commandAvailable("node")) return emptyList()
        val directory = installedDirectory(component)
        val entry = File(directory, component.entry)
        if (!validInstallation(component, directory) || !entry.isFile) return emptyList()
        return resolveCommand(listOf("node", entry.absolutePath, "--stdio"))
    }

    internal fun status(path: String?): String {
        val component = componentFor(path) ?: return "该语言尚无 Android 本地按需安装方案"
        return if (installedCommand(path).isNotEmpty()) {
            component.id + " " + component.version + " 已安装（待首次真实语义查询验证）"
        } else {
            component.id + " " + component.version + " 尚未安装；首次语义查询需确认后下载"
        }
    }

    /**
     * Caller must obtain an explicit installation approval before entering this method.
     * All state is kept outside workspace and can be reused by every session.
     */
    internal suspend fun ensureInstalled(path: String?): List<String> = installationMutex.withLock {
        installedCommand(path).takeIf { it.isNotEmpty() }?.let { return@withLock it }
        val component = componentFor(path)
            ?: error("此语言暂无可安装的 Android 本地语言服务器")
        check(commandAvailable("node")) { "内置 Node.js 尚未就绪" }
        runInterruptible(Dispatchers.IO) { install(component) }
        return@withLock installedCommand(path).takeIf { it.isNotEmpty() }
            ?: error("语言服务器安装结束，但入口验证失败")
    }

    private fun installedDirectory(component: Component) =
        File(home, "components/" + component.id + "-" + component.version)

    private fun validInstallation(component: Component, directory: File): Boolean {
        val marker = File(directory, ".ready")
        val entry = File(directory, component.entry)
        val actual = runCatching { marker.readText().trim() }.getOrNull()
        return actual == component.id + "@" + component.version && entry.isFile &&
            entry.length() > 0L && File(directory, "package-lock.json").isFile
    }

    private fun install(component: Component) {
        check(home.isDirectory || home.mkdirs()) { "无法创建语言组件目录" }
        val parent = checkNotNull(installedDirectory(component).parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建组件安装目录" }

        val npmCli = ensureNpm()
        val finalDir = installedDirectory(component)
        val stage = File(parent, "." + component.id + "-install")
        if (stage.exists()) check(stage.deleteRecursively()) { "无法清理中断的组件安装" }
        check(stage.mkdirs()) { "无法创建安装暂存目录" }
        try {
            val packages = when (component) {
                Component.TYPESCRIPT -> listOf("typescript-language-server@6.0.1", "typescript@5.9.3")
                Component.PYRIGHT -> listOf("pyright@1.1.410")
            }
            val node = resolveCommand(listOf("node")).first()
            val args = listOf(node, npmCli.absolutePath, "install") + packages + listOf(
                "--prefix", stage.absolutePath,
                "--ignore-scripts", "--omit=optional", "--no-audit", "--no-fund",
                "--save-exact", "--registry=https://registry.npmjs.org/",
            )
            runProcess(args, stage)
            check(File(stage, component.entry).isFile) {
                "下载的 " + component.id + " 包缺少 LSP 入口"
            }
            check(File(stage, "package-lock.json").isFile) { "缺少 npm 依赖锁文件" }
            // Mark ready only after dependency extraction and entry validation.
            File(stage, ".ready").writeText(component.id + "@" + component.version)
            if (finalDir.exists()) check(finalDir.deleteRecursively()) { "旧组件目录无法清理" }
            check(stage.renameTo(finalDir)) { "组件安装提交失败" }
            // A newer pinned version replaces the old one only after successful installation.
            parent.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith(component.id + "-") && it != finalDir }
                .forEach { old -> if (!old.deleteRecursively()) {
                    AppLog.warn("LspInstaller", "stale component cleanup failed: " + old.name)
                } }
            AppLog.info("LspInstaller", "installed " + component.id + "@" + component.version)
        } catch (error: Exception) {
            AppLog.failure("LspInstaller", "install_" + component.id, error)
            throw error
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    /** npm itself ships JS plus bundled dependencies; never run downloaded shell binaries. */
    private fun ensureNpm(): File {
        val cli = File(home, "bootstrap/npm-11.6.2/package/bin/npm-cli.js")
        if (cli.isFile && cli.length() > 0L) return cli
        val parent = File(home, "bootstrap")
        check(parent.isDirectory || parent.mkdirs()) { "无法创建 npm 暂存目录" }
        val archive = File(parent, "npm-11.6.2.tgz.part")
        val extracted = File(parent, ".npm-stage")
        if (extracted.exists()) extracted.deleteRecursively()
        try {
            var valid = false
            var lastError: Exception? = null
            repeat(3) { attempt ->
                if (valid) return@repeat
                try {
                    fetchArchive("https://registry.npmjs.org/npm/-/npm-11.6.2.tgz", archive)
                    check(fileDigest(archive, "SHA-512") == NPM_SHA512) {
                        "npm 引导包完整性校验失败（SHA-512）"
                    }
                    valid = true
                } catch (error: Exception) {
                    if (error is InterruptedException) throw error
                    lastError = error
                    archive.delete()
                    // Retrying is bounded; keep this synchronous IO path free of thread sleeps.
                }
            }
            check(valid) { "npm 引导包下载或校验失败：" + lastError?.message }
            check(extracted.mkdirs()) { "无法创建解压目录" }
            extractVerifiedTar(archive, extracted)
            val entry = File(extracted, "package/bin/npm-cli.js")
            check(entry.isFile && entry.length() > 0) { "npm 引导入口缺失" }
            val finalDir = File(home, "bootstrap/npm-11.6.2")
            if (finalDir.exists()) check(finalDir.deleteRecursively())
            check(extracted.renameTo(finalDir)) { "npm 引导组件提交失败" }
            return cli.also { check(it.isFile) }
        } finally {
            archive.delete()
            if (extracted.exists()) extracted.deleteRecursively()
        }
    }

    private fun runProcess(args: List<String>, directory: File) {
        val process = ProcessBuilder(args)
            .directory(directory)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(processEnvironment())
                environment()["npm_config_cache"] = File(home, "npm-cache").absolutePath
                environment()["npm_config_ignore_scripts"] = "true"
                environment()["npm_config_audit"] = "false"
                environment()["npm_config_fund"] = "false"
                environment()["npm_config_update_notifier"] = "false"
                environment()["npm_config_strict_ssl"] = "true"
            }
            .start()
        try {
            // Drain output concurrently; otherwise npm may deadlock on a full stdout pipe.
            val output = StringBuilder()
            val drain = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < 4_000) output.append(line.take(200)).append('\n')
                    }
                }
            }.apply { isDaemon = true; start() }
            val completed = process.waitFor(180, TimeUnit.SECONDS)
            if (!completed) error("安装超时（180 秒）")
            drain.join(1_000)
            check(process.exitValue() == 0) {
                "npm 安装失败（" + process.exitValue() + "）：" + output.takeLast(1_500)
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    internal fun uninstall(component: Component): Boolean {
        val parent = File(home, "components")
        return parent.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(component.id + "-") }
            .map { it.deleteRecursively() }
            .all { it }
    }

    companion object {
        // npm 11.6.2 registry dist.integrity, pinned independently of the network.
        const val NPM_SHA512 =
            "ee22b335fcbc95662cdf3ab8a053daf045d9cf9c6df6040d28965abb707512b2c16fa6c5eec049d34c74f78f390cebd14f697919eadb97756564d4f9eccc4954"
        const val MAX_TARBALL_BYTES = 24L * 1024 * 1024
        const val MAX_UNPACKED_BYTES = 90L * 1024 * 1024

        fun downloadArchive(address: String, dest: File) {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                val response = connection.responseCode
                check(response == 200) { "npm 引导包下载 HTTP $response" }
                val expected = connection.contentLengthLong.takeIf { it >= 0 }
                if (expected != null) check(expected <= MAX_TARBALL_BYTES) {
                    "npm 引导包超过限制"
                }
                var received = 0L
                FileOutputStream(dest).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            check(received <= MAX_TARBALL_BYTES) { "npm 引导包体积异常" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (expected != null && expected != received) {
                    throw IOException("npm 引导包短读：$received/$expected")
                }
                check(received > 0) { "npm 引导包为空" }
            } finally {
                connection.disconnect()
            }
        }

        fun fileDigest(file: File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        /** Regular files and directories only; refuses links, traversal and oversized archives. */
        fun extractVerifiedTar(archive: File, destination: File) {
            GZIPInputStream(archive.inputStream().buffered()).use { stream ->
                val header = ByteArray(512)
                var total = 0L
                var extendedPath: String? = null
                fun readExactly(target: ByteArray) {
                    var read = 0
                    while (read < target.size) {
                        val count = stream.read(target, read, target.size - read)
                        check(count > 0) { "tar 文件提前结束" }
                        read += count
                    }
                }
                fun consumePadding(size: Long) {
                    var padding = (512 - size % 512) % 512
                    while (padding > 0) {
                        val count = stream.skip(padding)
                        check(count > 0) { "tar 对齐数据不完整" }
                        padding -= count
                    }
                }
                while (true) {
                    readExactly(header)
                    if (header.all { it == 0.toByte() }) return
                    fun field(start: Int, length: Int) =
                        String(header, start, length, Charsets.UTF_8).substringBefore('\u0000').trim()
                    val name = field(0, 100)
                    val prefix = field(345, 155)
                    val ordinaryPath = if (prefix.isEmpty()) name else prefix + "/" + name
                    val size = field(124, 12).trim().toLongOrNull(8)
                        ?: error("tar 大小字段无效")
                    val kind = header[156].toInt().toChar()
                    check(size >= 0 && size <= MAX_UNPACKED_BYTES) { "tar 条目过大" }
                    total += size
                    check(total <= MAX_UNPACKED_BYTES) { "tar 解压体积超限" }

                    // npm pack emits PAX extended headers for long file paths.
                    // Only the path extension is needed; never accept arbitrary link types.
                    if (kind == 'x') {
                        check(size <= 256 * 1024) { "tar PAX 元数据过大" }
                        val payload = ByteArray(size.toInt())
                        readExactly(payload)
                        val metadata = payload.toString(Charsets.UTF_8)
                        extendedPath = metadata.lineSequence()
                            .map { it.substringAfter(' ', "").trimEnd('\n') }
                            .firstOrNull { it.startsWith("path=") }
                            ?.removePrefix("path=")
                        consumePadding(size)
                        continue
                    }

                    check(kind == '0' || kind == '\u0000' || kind == '5') {
                        "tar 包含不支持的链接或特殊文件"
                    }
                    val relative = extendedPath ?: ordinaryPath
                    extendedPath = null
                    check(relative.isNotBlank() && !relative.startsWith("/") &&
                        relative.split('/').none { it == ".." || it.isBlank() }) {
                        "tar 路径越界"
                    }
                    val target = File(destination, relative).canonicalFile
                    check(target.toPath().startsWith(destination.canonicalFile.toPath())) {
                        "tar 文件越界"
                    }
                    if (kind == '5') {
                        check(target.isDirectory || target.mkdirs()) { "创建目录失败" }
                    } else {
                        check(target.parentFile.isDirectory || target.parentFile.mkdirs())
                        target.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var remaining = size
                            while (remaining > 0) {
                                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                check(n > 0) { "tar 文件不完整" }
                                output.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                    }
                    if (kind == '5' && size > 0) {
                        var remaining = size
                        while (remaining > 0) {
                            val skipped = stream.skip(remaining)
                            check(skipped > 0) { "tar 目录数据不完整" }
                            remaining -= skipped
                        }
                    }
                    consumePadding(size)
                }
            }
        }
    }
}
