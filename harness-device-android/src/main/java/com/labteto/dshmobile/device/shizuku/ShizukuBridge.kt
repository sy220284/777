package com.labteto.dshmobile.device.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

data class ShizukuState(
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
    val uid: Int?,
)

class ShizukuBridge(
    private val context: Context,
) {
    private val connectionMutex = Mutex()
    @Volatile private var remote: IBinder? = null
    @Volatile private var pendingConnection: CompletableDeferred<IBinder>? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PrivilegedCommandService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("harness_privileged")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                pendingConnection?.completeExceptionally(IllegalStateException("Shizuku UserService 未返回 Binder"))
                return
            }
            remote = service
            pendingConnection?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    fun state(): ShizukuState {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return ShizukuState(false, false, null)
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val uid = runCatching { Shizuku.getUid() }.getOrNull()
        return ShizukuState(true, granted, uid)
    }

    fun requestPermission(requestCode: Int) {
        require(state().binderAlive) { "Shizuku 服务未运行" }
        if (!state().permissionGranted) Shizuku.requestPermission(requestCode)
    }

    suspend fun execute(command: String, timeoutMillis: Long = 30_000L): String {
        require(command.isNotBlank()) { "特权命令不能为空" }
        val current = state()
        require(current.binderAlive) { "Shizuku 服务未运行" }
        require(current.permissionGranted) { "Shizuku 权限未授予" }
        val binder = connect()
        return withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(PrivilegedCommandService.DESCRIPTOR)
                data.writeString(command)
                data.writeLong(timeoutMillis.coerceIn(1L, MAX_TIMEOUT_MILLIS))
                val accepted = binder.transact(
                    PrivilegedCommandService.TRANSACTION_EXECUTE,
                    data,
                    reply,
                    0,
                )
                require(accepted) { "Shizuku UserService 拒绝命令事务" }
                reply.readException()
                reply.readString().orEmpty()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun disconnect() {
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        remote = null
        pendingConnection = null
    }

    private suspend fun connect(): IBinder {
        remote?.takeIf(IBinder::isBinderAlive)?.let { return it }
        return connectionMutex.withLock {
            remote?.takeIf(IBinder::isBinderAlive)?.let { return@withLock it }
            val deferred = CompletableDeferred<IBinder>()
            pendingConnection = deferred
            Shizuku.bindUserService(serviceArgs, connection)
            try {
                withTimeout(CONNECT_TIMEOUT_MILLIS) { deferred.await() }
            } finally {
                pendingConnection = null
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val MAX_TIMEOUT_MILLIS = 15 * 60_000L
    }
}

class PrivilegedCommandService() : Binder() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != TRANSACTION_EXECUTE) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val command = data.readString().orEmpty()
        val timeoutMillis = data.readLong().coerceIn(1L, 15 * 60_000L)
        val result = executeCommand(command, timeoutMillis)
        reply?.writeNoException()
        reply?.writeString(result)
        return true
    }

    private fun executeCommand(command: String, timeoutMillis: Long): String {
        if (command.isBlank()) return "退出码：-1\n命令为空"
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        return try {
            val output = process.inputStream.bufferedReader()
            val collector = Thread {
                runCatching { output.readText() }
            }
            var text = ""
            val reader = Thread {
                text = runCatching { output.readText() }.getOrDefault("")
            }
            reader.start()
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor()
                reader.join(2_000)
                "[TIMEOUT] 特权命令超时\n$text"
            } else {
                reader.join(2_000)
                "退出码：${process.exitValue()}\n$text"
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    companion object {
        const val DESCRIPTOR = "com.labteto.dshmobile.device.shizuku.PrivilegedCommandService"
        const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
    }
}
