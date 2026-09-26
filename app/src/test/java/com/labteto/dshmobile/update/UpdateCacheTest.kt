package com.labteto.dshmobile.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `startup cleanup removes unowned legacy cache immediately`() {
        val cacheDir = temporary.newFolder("cache")
        val legacy = File(cacheDir, "updates/0.12.0-777.95/app-release.apk")
        legacy.parentFile?.mkdirs()
        legacy.writeBytes(ByteArray(8))

        val retry = UpdateCache.cleanupStale(
            cacheDir = cacheDir,
            currentVersionCode = 95L,
            nowMillis = 1_000_000L,
        )

        assertNull(retry)
        assertFalse(File(cacheDir, "updates").exists())
    }

    @Test
    fun `active installer handoff protects only verified apk until grace expires`() {
        val cacheDir = temporary.newFolder("cache")
        val root = UpdateCache.prepare(cacheDir)
        val apk = File(root, "app-release.apk").apply { writeBytes(ByteArray(8)) }
        val residue = File(root, "leftover.patch").apply { writeBytes(ByteArray(8)) }
        val handedAt = 1_000_000L

        UpdateCache.markInstallerHandoff(
            root = root,
            apk = apk,
            targetVersionCode = 101L,
            handedAtMillis = handedAt,
        )
        val retry = UpdateCache.cleanupStale(
            cacheDir = cacheDir,
            currentVersionCode = 100L,
            nowMillis = handedAt + 1_000L,
        )

        assertNotNull(retry)
        assertEquals(UpdateCache.INSTALLER_HANDOFF_GRACE_MS - 1_000L, retry)
        assertTrue(apk.exists())
        assertFalse(residue.exists())
        assertEquals(2, root.listFiles()?.size)
    }

    @Test
    fun `installed target version clears handoff immediately`() {
        val cacheDir = temporary.newFolder("cache")
        val root = UpdateCache.prepare(cacheDir)
        val apk = File(root, "app-release.apk").apply { writeBytes(ByteArray(8)) }

        UpdateCache.markInstallerHandoff(
            root = root,
            apk = apk,
            targetVersionCode = 101L,
            handedAtMillis = 1_000_000L,
        )
        val retry = UpdateCache.cleanupStale(
            cacheDir = cacheDir,
            currentVersionCode = 101L,
            nowMillis = 1_001_000L,
        )

        assertNull(retry)
        assertFalse(root.exists())
    }

    @Test
    fun `cancelled installer handoff is removed after bounded grace window`() {
        val cacheDir = temporary.newFolder("cache")
        val root = UpdateCache.prepare(cacheDir)
        val apk = File(root, "app-release.apk").apply { writeBytes(ByteArray(8)) }
        val handedAt = 1_000_000L

        UpdateCache.markInstallerHandoff(
            root = root,
            apk = apk,
            targetVersionCode = 101L,
            handedAtMillis = handedAt,
        )
        val retry = UpdateCache.cleanupStale(
            cacheDir = cacheDir,
            currentVersionCode = 100L,
            nowMillis = handedAt + UpdateCache.INSTALLER_HANDOFF_GRACE_MS,
        )

        assertNull(retry)
        assertFalse(root.exists())
    }

    @Test
    fun `discard removes failed staging attempt immediately`() {
        val cacheDir = temporary.newFolder("cache")
        val root = UpdateCache.prepare(cacheDir)
        File(root, "app-release.apk").writeBytes(ByteArray(8))
        File(root, "app-release.apk.part").writeBytes(ByteArray(8))

        UpdateCache.discard(root)

        assertFalse(root.exists())
    }
}
