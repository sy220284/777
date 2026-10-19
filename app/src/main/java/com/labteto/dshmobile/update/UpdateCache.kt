package com.labteto.dshmobile.update

import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import com.labteto.dshmobile.observability.AppLog

/**
 * Update artifacts are installer staging files, not a persistent download cache.
 *
 * Legacy/unowned files are removed immediately. A verified APK handed to Android's package
 * installer carries a tiny ownership marker so a process restart cannot delete the file while the
 * installer may still be reading it. Once the target version is installed, or the short handoff
 * window expires, the entire staging directory is removed.
 */
internal object UpdateCache {
    internal const val INSTALLER_HANDOFF_GRACE_MS = 30L * 60L * 1000L

    private const val DIRECTORY_NAME = "updates"
    private const val HANDOFF_MARKER = ".installer-handoff"
    private const val HANDOFF_SCHEMA = "1"
    private val SAFE_APK_NAME = Regex("[A-Za-z0-9._-]+")

    private data class InstallerHandoff(
        val apkName: String,
        val targetVersionCode: Long,
        val handedAtMillis: Long,
    )

    @Synchronized
    fun prepare(
        cacheDir: File,
        currentVersionCode: Long,
        nowMillis: Long,
    ): File {
        val root = File(cacheDir, DIRECTORY_NAME)
        val protectedForMillis = cleanupStale(
            cacheDir = cacheDir,
            currentVersionCode = currentVersionCode,
            nowMillis = nowMillis,
        )
        // cleanupStale() is also called from startup, where an active handoff must remain
        // protected. prepare(), however, is an explicit new user attempt: Android gives this
        // ACTION_VIEW flow no cancellation callback, so retaining the old marker here would lock
        // the user out for the full grace window after dismissing the installer.
        if (protectedForMillis != null && root.exists() && !root.deleteRecursively()) {
            throw IOException("无法释放上一份安装交接缓存，请稍后重试")
        }
        if (root.exists() && !root.deleteRecursively()) {
            throw IOException("无法清理旧更新缓存，请释放存储空间后重试")
        }
        if (!root.mkdirs() && !root.isDirectory) {
            throw IOException("无法创建更新临时目录")
        }
        return root
    }

    @Synchronized
    fun markInstallerHandoff(
        root: File,
        apk: File,
        targetVersionCode: Long,
        handedAtMillis: Long,
    ) {
        require(apk.isFile) { "安装 APK 不存在" }
        require(apk.parentFile?.canonicalFile == root.canonicalFile) {
            "安装 APK 不在更新临时目录内"
        }
        require(apk.name.matches(SAFE_APK_NAME)) { "安装 APK 文件名不安全" }
        require(targetVersionCode > 0L) { "目标版本号无效" }
        require(handedAtMillis > 0L) { "安装交接时间无效" }

        writeHandoff(
            root,
            InstallerHandoff(
                apkName = apk.name,
                targetVersionCode = targetVersionCode,
                handedAtMillis = handedAtMillis,
            ),
        )
    }

    @Synchronized
    fun discard(root: File) {
        root.deleteRecursively()
    }

    /**
     * Removes stale update data and returns a delay before one protected installer handoff should
     * be checked again. A null result means there is nothing left that needs delayed cleanup.
     */
    @Synchronized
    fun cleanupStale(
        cacheDir: File,
        currentVersionCode: Long,
        nowMillis: Long,
    ): Long? {
        val root = File(cacheDir, DIRECTORY_NAME)
        if (!root.isDirectory) return null

        var handoff = readHandoff(root)
        if (handoff == null) {
            // No marker means unowned staging data. An existing but unreadable/corrupt marker
            // cannot prove the APK was never handed to the system installer.
            val marker = File(root, HANDOFF_MARKER).toPath()
            if (!Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
                AppLog.warn("UpdateCache", "安装交接标记损坏或不可读取，暂时保留安装 APK")
                return INSTALLER_HANDOFF_GRACE_MS
            }
            root.deleteRecursively()
            return null
        }
        if (nowMillis > 0L && handoff.handedAtMillis > nowMillis) {
            handoff = handoff.copy(handedAtMillis = nowMillis)
            writeHandoff(root, handoff)
        }

        val apk = File(root, handoff.apkName)
        if (!apk.isFile ||
            currentVersionCode >= handoff.targetVersionCode
        ) {
            root.deleteRecursively()
            return null
        }

        val elapsed = (nowMillis - handoff.handedAtMillis).coerceAtLeast(0L)
        if (elapsed >= INSTALLER_HANDOFF_GRACE_MS) {
            root.deleteRecursively()
            return null
        }

        // Keep exactly the file the installer owns plus its marker. Anything else is a failed
        // download, patch, intermediate APK, or legacy residue and can go immediately.
        root.listFiles()?.forEach { artifact ->
            if (artifact.name != handoff.apkName && artifact.name != HANDOFF_MARKER) {
                artifact.deleteRecursively()
            }
        }
        return INSTALLER_HANDOFF_GRACE_MS - elapsed
    }

    private fun writeHandoff(root: File, handoff: InstallerHandoff) {
        val marker = File(root, HANDOFF_MARKER)
        val temp = File(root, "$HANDOFF_MARKER.tmp")
        val bytes = listOf(
            HANDOFF_SCHEMA,
            handoff.apkName,
            handoff.targetVersionCode.toString(),
            handoff.handedAtMillis.toString(),
        ).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        // Never delete the old live marker before its durable replacement is ready.
        try {
            Files.move(temp.toPath(), marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readHandoff(root: File): InstallerHandoff? {
        val marker = File(root, HANDOFF_MARKER)
        val path = marker.toPath()
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val lines = try {
            marker.readLines()
        } catch (error: java.io.IOException) {
            AppLog.warn("UpdateCache", "安装交接标记读取失败，暂不清理 APK", error)
            return null
        }
        if (lines.size < 4 || lines[0] != HANDOFF_SCHEMA) return null

        val apkName = lines[1]
        val targetVersionCode = lines[2].toLongOrNull() ?: return null
        val handedAtMillis = lines[3].toLongOrNull() ?: return null
        if (!apkName.matches(SAFE_APK_NAME) ||
            targetVersionCode <= 0L ||
            handedAtMillis <= 0L
        ) {
            return null
        }
        return InstallerHandoff(
            apkName = apkName,
            targetVersionCode = targetVersionCode,
            handedAtMillis = handedAtMillis,
        )
    }
}
