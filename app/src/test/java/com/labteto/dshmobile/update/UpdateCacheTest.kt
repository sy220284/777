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

        val prepared = prepare(cacheDir)

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
        val root = prepare(cacheDir)
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
    fun `explicit new attempt supersedes an active installer handoff`() {
        val cacheDir = temporary.newFolder("cache")
        val handedAt = 1_000_000L
        val root = prepare(cacheDir, currentVersionCode = 100L, nowMillis = handedAt)
        val apk = File(root, "app-release.apk").apply { writeBytes(ByteArray(8)) }

        UpdateCache.markInstallerHandoff(
            root = root,
            apk = apk,
            targetVersionCode = 101L,
            handedAtMillis = handedAt,
        )

        val replacement = prepare(
            cacheDir = cacheDir,
            currentVersionCode = 100L,
            nowMillis = handedAt + 1_000L,
        )

        assertFalse(apk.exists())
        assertTrue(replacement.isDirectory)
        assertTrue(replacement.listFiles().isNullOrEmpty())
    }

    @Test
    fun `installed target version clears handoff immediately`() {
        val cacheDir = temporary.newFolder("cache")
        val root = prepare(cacheDir)
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
        val root = prepare(cacheDir)
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
        val root = prepare(cacheDir)
        File(root, "app-release.apk").writeBytes(ByteArray(8))
        File(root, "app-release.apk.part").writeBytes(ByteArray(8))

        UpdateCache.discard(root)

        assertFalse(root.exists())
    }

    private fun prepare(
        cacheDir: File,
        currentVersionCode: Long = 100L,
        nowMillis: Long = 1_000_000L,
    ): File = UpdateCache.prepare(
        cacheDir = cacheDir,
        currentVersionCode = currentVersionCode,
        nowMillis = nowMillis,
    )
    @Test
    fun `clock rollback rebases handoff so cleanup remains bounded`() {
        val cacheDir = temporary.newFolder("cache")
        val root = prepare(cacheDir, currentVersionCode = 100L, nowMillis = 2_000_000L)
        val apk = File(root, "app-release.apk").apply { writeBytes(ByteArray(8)) }

        UpdateCache.markInstallerHandoff(
            root = root,
            apk = apk,
            targetVersionCode = 101L,
            handedAtMillis = 2_000_000L,
        )

        val rebasedAt = 1_000_000L
        assertEquals(
            UpdateCache.INSTALLER_HANDOFF_GRACE_MS,
            UpdateCache.cleanupStale(
                cacheDir = cacheDir,
                currentVersionCode = 100L,
                nowMillis = rebasedAt,
            ),
        )
        assertTrue(root.exists())

        assertNull(
            UpdateCache.cleanupStale(
                cacheDir = cacheDir,
                currentVersionCode = 100L,
                nowMillis = rebasedAt + UpdateCache.INSTALLER_HANDOFF_GRACE_MS,
            ),
        )
        assertFalse(root.exists())
    }
}
