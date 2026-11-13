package com.github.sisong

/**
 * HDiffPatch Android 原生补丁库的最小 JNI 桥。
 *
 * 类名、方法名和签名属于 JNI ABI；R8 不得改写。
 */
object HPatch {
    init {
        System.loadLibrary("hpatchz")
    }

    /**
     * 成功返回 0；非 0 为 HDiffPatch 错误码。
     */
    @JvmStatic
    external fun patch(
        oldFileName: String,
        diffFileName: String,
        outNewFileName: String,
        cacheMemory: Long,
        threadNum: Int,
        checksumNewData: Boolean,
    ): Int
}
