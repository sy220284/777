package com.labteto.dshmobile.runtime

/**
 * Optional JNI bridge for a real Android pseudo-terminal.
 *
 * The JVM runtime module stays testable without Android/NDK: loading failure simply reports the
 * bridge as unavailable and [PersistentPipeTerminalProvider] keeps its pipe fallback.
 */
internal object NativePtyBridge {
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("dshpty")
            nativeProbe()
        }.getOrDefault(false)
    }

    fun open(
        command: List<String>,
        workingDirectory: String?,
        environment: Map<String, String>,
        rows: Int = DEFAULT_ROWS,
        columns: Int = DEFAULT_COLUMNS,
    ): Long {
        check(available) { "原生 PTY 不可用" }
        require(command.isNotEmpty()) { "PTY 命令不能为空" }
        require(rows > 0 && columns > 0) { "PTY 行列必须大于 0" }
        val entries = environment.entries
            .sortedBy(Map.Entry<String, String>::key)
            .map { (key, value) -> "$key=$value" }
            .toTypedArray()
        return nativeOpen(
            command = command.toTypedArray(),
            workingDirectory = workingDirectory,
            environment = entries,
            rows = rows,
            columns = columns,
        ).also { handle ->
            check(handle != 0L) { "原生 PTY 未返回有效句柄" }
        }
    }

    fun read(handle: Long, maxBytes: Int): ByteArray {
        require(handle != 0L) { "PTY 句柄无效" }
        require(maxBytes in 1..MAX_NATIVE_READ_BYTES) { "PTY 读取大小无效：$maxBytes" }
        return nativeRead(handle, maxBytes)
    }

    fun write(handle: Long, bytes: ByteArray) {
        require(handle != 0L) { "PTY 句柄无效" }
        if (bytes.isEmpty()) return
        nativeWrite(handle, bytes)
    }

    fun resize(handle: Long, columns: Int, rows: Int): Boolean {
        require(handle != 0L) { "PTY 句柄无效" }
        require(columns > 0 && rows > 0) { "PTY 行列必须大于 0" }
        return nativeResize(handle, columns, rows)
    }

    fun isAlive(handle: Long): Boolean =
        handle != 0L && nativeIsAlive(handle)

    fun hasReadable(handle: Long): Boolean =
        handle != 0L && nativeHasReadable(handle)

    fun close(handle: Long) {
        if (handle != 0L) nativeClose(handle)
    }

    private external fun nativeProbe(): Boolean

    private external fun nativeOpen(
        command: Array<String>,
        workingDirectory: String?,
        environment: Array<String>,
        rows: Int,
        columns: Int,
    ): Long

    private external fun nativeRead(handle: Long, maxBytes: Int): ByteArray
    private external fun nativeWrite(handle: Long, bytes: ByteArray)
    private external fun nativeResize(handle: Long, columns: Int, rows: Int): Boolean
    private external fun nativeIsAlive(handle: Long): Boolean
    private external fun nativeHasReadable(handle: Long): Boolean
    private external fun nativeClose(handle: Long)

    private const val DEFAULT_ROWS = 24
    private const val DEFAULT_COLUMNS = 80
    private const val MAX_NATIVE_READ_BYTES = 64 * 1024
}
