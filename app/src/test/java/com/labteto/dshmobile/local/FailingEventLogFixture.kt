package com.labteto.dshmobile.local

import java.io.File

/** Break writes only after SessionEventLog has opened its OS writer lease. */
internal fun blockEventLogWritesAfterOpen(eventFile: File) {
    val writerLock = File(eventFile.path + ".writer.lock")
    check(writerLock.delete()) { "测试前置：必须清理已创建的写入锁 ${writerLock.path}" }
    val directory = requireNotNull(eventFile.parentFile)
    check(directory.delete()) { "测试前置：日志目录必须为空 ${directory.path}" }
    check(directory.createNewFile()) { "测试前置：无法注入不可写目录 ${directory.path}" }
}
