package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalStreamPreview
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

    @Test
    fun boundedTailNeverSplitsEmojiSurrogatePair() {
        var now = 0L
        val published = mutableListOf<String>()
        val preview = LocalStreamPreview(3, 50, { now }, published::add)

        preview.append("A😀B")
        assertEquals(listOf("😀B"), published)

        now = 1
        preview.append("C")
        preview.flush()
        assertEquals("BC", published.last())
    }

    @Test
    fun oversizedSingleDeltaKeepsOnlyBoundedTail() {
        var now = 0L
        val published = mutableListOf<String>()
        val preview = LocalStreamPreview(4, 50, { now }, published::add)

        preview.append("123456789")

        assertEquals(listOf("6789"), published)
    }

    @Test
    fun circularBufferMatchesOriginalTailAndSurrogateRulesUnderRandomChunks() {
        val random = kotlin.random.Random(20261021)
        for (capacity in listOf(1, 2, 3, 4, 8, 17, 64)) {
            val published = mutableListOf<String>()
            val preview = LocalStreamPreview(capacity, 0, { 0L }, published::add)
            val original = StringBuilder()
            val fragments = listOf("a", "中文", "😀", "👩‍💻", "x", "\ud83d", "\ude00", "", "123456789")
            val expected = mutableListOf<String>()
            repeat(450) {
                val delta = fragments[random.nextInt(fragments.size)]
                if (delta.isEmpty()) return@repeat
                if (delta.length >= capacity) {
                    original.clear()
                    original.append(
                        com.labteto.dshmobile.local.model.takeLastWithoutSplittingSurrogatePair(delta, capacity),
                    )
                } else {
                    var overflow = original.length + delta.length - capacity
                    if (overflow > 0 && overflow < original.length &&
                        Character.isHighSurrogate(original[overflow - 1]) &&
                        Character.isLowSurrogate(original[overflow])) overflow++
                    if (overflow > 0) original.delete(0, overflow.coerceAtMost(original.length))
                    original.append(delta)
                }
                expected += original.toString()
                preview.append(delta)
            }
            assertEquals("capacity=$capacity", expected, published)
        }
    }

}
