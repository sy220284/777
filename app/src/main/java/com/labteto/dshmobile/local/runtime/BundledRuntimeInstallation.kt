package com.labteto.dshmobile.local.runtime

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import com.labteto.dshmobile.local.persistence.DurableFileCommit

/** Small installation primitives; each runtime still owns ABI, payload and environment contracts. */
internal object BundledRuntimeInstallation {
    /**
     * Shared runtime blobs are installed by both the app and instrumentation processes.
     * A JVM monitor is process-local; the ABI lock must cover validation, deletion, linking,
     * initialization and marker publication as one cross-process transaction.
     */
    @Synchronized
    fun prepareVersion(context: Context, runtime: String, versionRoot: File, version: String, abi: String,
        extraReady: () -> Boolean = { true },
        initialize: () -> Unit = {},
    ) {
        val lockDir = File(context.noBackupFilesDir, "runtime/shared/$abi")
        check(lockDir.isDirectory || lockDir.mkdirs()) { "无法建立共享运行库锁目录：${lockDir.path}" }
        val lockFile = File(lockDir, ".runtime-install.lock")
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val libraryDir = File(versionRoot, "lib")
                val marker = File(versionRoot, ".ready")
                val expected = "$version|" + BundledRuntimeLibraryStore.LAYOUT_VERSION
                val prepared = runCatching { marker.readText().trim() }.getOrNull() == expected
                if (prepared && BundledRuntimeLibraryStore.isMaterializedValid(context, runtime, abi, libraryDir) &&
                    extraReady()) {
                    return
                }
                check(!versionRoot.exists() || versionRoot.deleteRecursively()) { "无法清理旧运行库：${versionRoot.path}" }
                BundledRuntimeLibraryStore.materialize(context, runtime, abi, libraryDir)
                initialize()
                DurableFileCommit.replace(marker, expected.toByteArray())
            }
        }
    }

    fun linkExecutable(target: File, link: File) {
        require(target.isFile && target.canExecute()) { "内置可执行文件不可用：${target.path}" }
        check(requireNotNull(link.parentFile).isDirectory || link.parentFile!!.mkdirs())
        Files.deleteIfExists(link.toPath())
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    fun removeOldVersions(home: File, version: String) {
        home.listFiles()?.filter { it.isDirectory && it.name != version }?.forEach {
            check(it.deleteRecursively()) { "无法清理旧运行库：${it.path}" }
        }
    }

    fun copyAssetDirectory(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        require(children.isNotEmpty()) { "内置运行库目录为空：$assetPath" }
        children.forEach { name ->
            val childAsset = "$assetPath/$name"
            val destination = File(target, name)
            if (context.assets.list(childAsset).orEmpty().isNotEmpty()) {
                check(destination.isDirectory || destination.mkdirs())
                copyAssetDirectory(context, childAsset, destination)
            } else {
                destination.parentFile?.mkdirs()
                context.assets.open(childAsset).use { input -> destination.outputStream().use(input::copyTo) }
                check(destination.setReadable(true, true) && destination.setWritable(true, true))
                check(destination.setExecutable(false, false))
            }
        }
    }
}
