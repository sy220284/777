package com.labteto.dshmobile.update

import java.io.File
import java.io.IOException

/**
 * Update artifacts are staging files, not a persistent download cache.
 *
 * A full APK has to stay readable after Android's installer activity is launched, so it cannot be
 * deleted immediately. The next app process start removes artifacts created by the previous
 * process, while a new update attempt always starts from an empty staging directory.
 */
internal object UpdateCache {
    private const val DIRECTORY_NAME = "updates"

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
    fun cleanupStale(cacheDir: File, processStartedAtMillis: Long) {
        val root = File(cacheDir, DIRECTORY_NAME)
        root.listFiles()?.forEach { artifact ->
            if (artifact.lastModified() < processStartedAtMillis) {
                artifact.deleteRecursively()
            }
        }
    }
}
