package com.labteto.dshmobile.local.model

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex

/**
 * The Media Owner has one writer for each provider task. The local coroutine lease
 * prevents two calls in this process from racing for a JVM FileLock; the OS lease
 * protects the same .part / checkpoint pair across separate Android processes.
 */
internal class LocalVideoTaskLeaseManager(private val directory: File) {
    private class Lease(val mutex: Mutex = Mutex(), var clients: Int = 0)
    private val leases = mutableMapOf<String, Lease>()

    private fun register(taskId: String): Lease {
        require(taskId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "视频任务身份无效" }
        return synchronized(leases) {
            leases.getOrPut(taskId) { Lease() }.also { it.clients++ }
        }
    }

    private fun unregister(taskId: String, lease: Lease) = synchronized(leases) {
        lease.clients--
        check(lease.clients >= 0) { "视频任务租约计数不匹配" }
        if (lease.clients == 0) check(leases.remove(taskId, lease))
    }

    private fun open(taskId: String): FileChannel {
        check(directory.isDirectory || directory.mkdirs()) { "视频存储目录无法创建" }
        return FileChannel.open(File(directory, ".video-$taskId.writer.lock").toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    }

    suspend fun <T> withLease(taskId: String, operation: suspend () -> T): T {
        val lease = register(taskId)
        try {
            lease.mutex.lock()
            try {
                open(taskId).use { channel ->
                    val lock = runInterruptible(Dispatchers.IO) { channel.lock() }
                    lock.use { return operation() }
                }
            } finally {
                lease.mutex.unlock()
            }
        } finally {
            unregister(taskId, lease)
        }
    }

    /** Deletion must not suspend a visible command or erase an active transfer. */
    fun <T> withExclusiveDeletion(taskId: String, operation: () -> T): T {
        val lease = register(taskId)
        try {
            check(lease.mutex.tryLock()) { "视频仍在下载，请先停止任务再清理断点" }
            try {
                open(taskId).use { channel ->
                    val lock = channel.tryLock()
                        ?: error("另一进程仍在保存此视频，暂不能删除断点")
                    lock.use { return operation() }
                }
            } finally {
                lease.mutex.unlock()
            }
        } finally {
            unregister(taskId, lease)
        }
    }
}
