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
            .version(2)
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

    suspend fun execute(command: List<String>, timeoutMillis: Long = 30_000L): String {
        validatePrivilegedCommand(command)
        val current = state()
        require(current.binderAlive) { "Shizuku 服务未运行" }
        require(current.permissionGranted) { "Shizuku 权限未授予" }
        val binder = connect()
        return withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(PrivilegedCommandService.DESCRIPTOR)
                data.writeStringList(command)
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


internal fun validatePrivilegedCommand(command: List<String>) {
    require(command.isNotEmpty()) { "特权命令不能为空" }
    require(command.size <= 32) { "特权命令参数过多" }
    require(command.all { it.length <= 4_096 && '\u0000' !in it && '\n' !in it && '\r' !in it }) {
        "特权命令参数非法"
    }
    when (command.first()) {
        "/system/bin/am" -> {
            require(command.size == 3 && command[1] == "force-stop") { "仅允许 am force-stop" }
            require(ANDROID_PACKAGE.matches(command[2])) { "应用包名非法" }
        }
        "/system/bin/settings" -> {
            require(command.size == 5 && command[1] == "put") { "仅允许 settings put" }
            require(command[2] in setOf("global", "secure", "system")) { "settings namespace 非法" }
            require(command[3].isNotBlank() && command[3].length <= 256) { "settings key 非法" }
        }
        "/system/bin/dumpsys" -> {
            require(command.size >= 2) { "dumpsys 缺少服务名" }
            require(SERVICE_NAME.matches(command[1])) { "dumpsys 服务名非法" }
        }
        "/system/bin/pm" -> {
            require(command == listOf("/system/bin/pm", "list", "packages")) {
                "仅允许 pm list packages"
            }
        }
        else -> error("特权命令不在允许列表")
    }
}

private val ANDROID_PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
private val SERVICE_NAME = Regex("[A-Za-z0-9_.:-]{1,128}")

class PrivilegedCommandService() : Binder() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != TRANSACTION_EXECUTE) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val command = data.createStringArrayList().orEmpty()
        val timeoutMillis = data.readLong().coerceIn(1L, 15 * 60_000L)
        val result = executeCommand(command, timeoutMillis)
        reply?.writeNoException()
        reply?.writeString(result)
        return true
    }

    private fun executeCommand(command: List<String>, timeoutMillis: Long): String {
        validatePrivilegedCommand(command)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        return try {
            val output = process.inputStream
            var text = ""
            val reader = Thread {
                text = runCatching { output.readBoundedText(MAX_OUTPUT_CHARS) }.getOrDefault("")
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

    private fun java.io.InputStream.readBoundedText(maxChars: Int): String {
        bufferedReader().use { reader ->
            val output = StringBuilder(minOf(maxChars, 16 * 1024))
            val buffer = CharArray(8 * 1024)
            var truncated = false
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                val remaining = maxChars - output.length
                if (remaining > 0) output.append(buffer, 0, minOf(read, remaining))
                if (read > remaining) truncated = true
            }
            if (truncated) output.append("\n[输出超过 ${maxChars} 字符，已截断]")
            return output.toString()
        }
    }

    companion object {
        // Binder has a process-wide transaction buffer near 1 MiB. Keep the
        // returned UTF-16 String comfortably below it after Parcel overhead.
        private const val MAX_OUTPUT_CHARS = 262_144
        const val DESCRIPTOR = "com.labteto.dshmobile.device.shizuku.PrivilegedCommandService"
        const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
    }
}
