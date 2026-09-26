package com.labteto.dshmobile.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `prepare removes all previous update artifacts`() {
        val cacheDir = temporary.newFolder("cache")
        val legacy = File(cacheDir, "updates/0.12.0-777.95/app-release.apk")
        legacy.parentFile?.mkdirs()
        legacy.writeBytes(ByteArray(32))

        val prepared = UpdateCache.prepare(cacheDir)

        assertTrue(prepared.isDirectory)
        assertFalse(legacy.exists())
        assertTrue(prepared.listFiles().isNullOrEmpty())
    }

    @Test
    fun `startup cleanup removes only artifacts from the previous process`() {
        val cacheDir = temporary.newFolder("cache")
        val root = File(cacheDir, "updates").apply { mkdirs() }
        val cutoff = System.currentTimeMillis()

        val stale = File(root, "old.apk").apply {
            writeBytes(ByteArray(8))
            setLastModified(cutoff - 1_000L)
        }
        val current = File(root, "current.apk").apply {
            writeBytes(ByteArray(8))
            setLastModified(cutoff + 1_000L)
        }

        UpdateCache.cleanupStale(cacheDir, cutoff)

        assertFalse(stale.exists())
        assertTrue(current.exists())
    }
}
