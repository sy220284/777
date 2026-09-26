package com.labteto.dshmobile.update

import java.io.File
import java.io.IOException

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
    fun prepare(cacheDir: File): File {
        val root = File(cacheDir, DIRECTORY_NAME)
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

        val marker = File(root, HANDOFF_MARKER)
        val temp = File(root, "$HANDOFF_MARKER.tmp")
        temp.writeText(
            listOf(
                HANDOFF_SCHEMA,
                apk.name,
                targetVersionCode.toString(),
                handedAtMillis.toString(),
            ).joinToString("\n", postfix = "\n"),
        )
        marker.delete()
        if (!temp.renameTo(marker)) {
            temp.copyTo(marker, overwrite = true)
            temp.delete()
        }
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

        val handoff = readHandoff(root)
        if (handoff == null) {
            root.deleteRecursively()
            return null
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

    private fun readHandoff(root: File): InstallerHandoff? {
        val marker = File(root, HANDOFF_MARKER)
        if (!marker.isFile) return null
        val lines = runCatching { marker.readLines() }.getOrNull() ?: return null
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
