package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalStreamPreviewTest {
    @Test
    fun publishesFirstTextImmediatelyAndCombinesLaterDeltas() {
        var now = 0L
        val published = mutableListOf<String>()
        val preview = LocalStreamPreview(10, 50, { now }, published::add)

        preview.append("你")
        now = 10
        preview.append("好")
        now = 49
        preview.append("！")
        assertEquals(listOf("你"), published)

        now = 50
        preview.append("完成")
        assertEquals(listOf("你", "你好！完成"), published)
    }

    @Test
    fun flushesLastBurstAndBoundsLongReply() {
        var now = 0L
        val published = mutableListOf<String>()
        val preview = LocalStreamPreview(4, 50, { now }, published::add)

        preview.append("123")
        now = 1
        preview.append("456")
        preview.flush()
        preview.flush()

        assertEquals(listOf("123", "3456"), published)
    }
}
