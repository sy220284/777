package com.labteto.dshmobile.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Boundary tests for the firmware-anchored sandbox.
 *
 * The guarantee under test is: firmware and kernel control surfaces are denied, while workspace and
 * shared-storage roots are reachable and eligible for auto-approval.
 */
class LocalSandboxBoundaryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun workspace(): File = temporaryFolder.newFolder("workspace")

    private fun shared(name: String): File = temporaryFolder.newFolder(name)

    @Test
    fun workspaceOnlyBoundaryStillDeniesEverythingElse() {
        val ws = workspace()
        val boundary = LocalSandboxBoundary.workspaceOnly(ws)

        assertTrue(boundary.isAllowed(File(ws, "a.txt")))
        assertTrue(boundary.canAutoApprove(File(ws, "a.txt")))

        val outside = temporaryFolder.newFolder("outside")
        assertFalse(boundary.isAllowed(File(outside, "x.txt")))
    }

    @Test
    fun firmwarePrefixesAreDenied() {
        val boundary = LocalSandboxBoundary.workspaceOnly(workspace())

        for (prefix in LocalSandboxBoundary.DEFAULT_FORBIDDEN_PREFIXES) {
            assertTrue("应拒绝固件路径: $prefix", boundary.isForbidden(File(prefix)))
            assertFalse("不应允许: $prefix", boundary.isAllowed(File(prefix)))
            assertFalse(boundary.canAutoApprove(File("$prefix/some/file")))
        }
    }

    @Test
    fun firmwareDenialCoversChildrenNotJustExactMatches() {
        val boundary = LocalSandboxBoundary.workspaceOnly(workspace())
        assertTrue(boundary.isForbidden(File("/system/build.prop")))
        assertTrue(boundary.isForbidden(File("/system/bin/sh")))
        assertTrue(boundary.isForbidden(File("/vendor/lib64/libc.so")))
    }

    @Test
    fun aPathSharingAPrefixNameIsNotFirmware() {
        val boundary = LocalSandboxBoundary.workspaceOnly(workspace())
        // `/systematic` must not match the `/system` prefix, so it is not classified as firmware.
        assertFalse(boundary.isForbidden(File("/systematic/data.txt")))
        // It is still outside every known root, so the fail-closed branch denies it.
        assertFalse(boundary.isAllowed(File("/systematic/data.txt")))
    }

    @Test
    fun otherAppsPrivateDataIsNotClassifiedAsFirmware() {
        val boundary = LocalSandboxBoundary.workspaceOnly(workspace())
        // User-installed apps are not firmware: the per-app sandbox is what keeps them out, so
        // classification must not claim credit for it. They are simply outside the roots.
        assertFalse(boundary.isForbidden(File("/data/data/com.example.other")))
        assertFalse(boundary.isAllowed(File("/data/data/com.example.other")))
    }

    @Test
    fun sharedStorageRootIsReachableAndAutoApproved() {
        val ws = workspace()
        val downloads = shared("Download")
        val boundary = LocalSandboxBoundary(
            workspaceRoot = ws,
            userRoots = listOf(downloads),
        )

        val target = File(downloads, "report.txt")
        assertTrue("共享存储应可达", boundary.isAllowed(target))
        assertTrue("共享存储写入应免弹窗", boundary.canAutoApprove(target))
    }

    @Test
    fun traversalFromASharedRootCannotReachFirmware() {
        val ws = workspace()
        val downloads = shared("Download")
        val boundary = LocalSandboxBoundary(workspaceRoot = ws, userRoots = listOf(downloads))

        // `..` leaves every known root, so the fail-closed branch denies it.
        assertFalse(boundary.isAllowed(File(downloads, "../../etc/passwd")))
    }

    @Test
    fun unknownPathInsideNoRootIsDenied() {
        val ws = workspace()
        val boundary = LocalSandboxBoundary(workspaceRoot = ws, userRoots = listOf(shared("Download")))
        val stray = temporaryFolder.newFolder("stray")
        assertFalse("不在任何根内的路径应拒绝", boundary.isAllowed(File(stray, "x.txt")))
    }
}
