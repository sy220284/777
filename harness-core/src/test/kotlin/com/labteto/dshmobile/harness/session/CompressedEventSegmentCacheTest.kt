package com.labteto.dshmobile.harness.session

import java.io.IOException
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompressedEventSegmentCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun repeatedReadSharesDecodeAndExplicitInvalidationReloads() {
        val file = temporary.newFile("events.part-1.gz")
        val expected = "first\nsecond\n".toByteArray()
        GZIPOutputStream(file.outputStream()).use { it.write(expected) }
        val first = CompressedEventSegmentCache.read(file, 1024)
        assertSame(first, CompressedEventSegmentCache.read(file, 1024))
        assertArrayEquals(expected, first)
        CompressedEventSegmentCache.invalidate(file.canonicalPath)
        assertNotSame(first, CompressedEventSegmentCache.read(file, 1024))
    }
    @Test(expected = IOException::class)
    fun cachedReadStillEnforcesCallersDecodeLimit() {
        val file = temporary.newFile("events.part-2.gz")
        GZIPOutputStream(file.outputStream()).use { it.write(ByteArray(128)) }
        CompressedEventSegmentCache.read(file, 1024)
        CompressedEventSegmentCache.read(file, 64)
    }
    @Test(expected = IOException::class)
    fun compressedExpansionIsBoundedBeforeCaching() {
        val file = temporary.newFile("events.part-3.gz")
        GZIPOutputStream(file.outputStream()).use { it.write(ByteArray(4096)) }
        CompressedEventSegmentCache.read(file, 64)
    }
}
