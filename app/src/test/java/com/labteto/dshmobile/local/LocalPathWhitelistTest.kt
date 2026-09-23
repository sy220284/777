package com.labteto.dshmobile.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Boundary tests for the multi-root path whitelist that replaced the single-root check. */
class LocalPathWhitelistTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun workspaceRoot(): File = temporaryFolder.newFolder("workspace")

    private fun externalRoot(name: String): File = temporaryFolder.newFolder(name)

    @Test
    fun workspaceOnlyFactoryKeepsThePreviousSandbox() {
        val workspace = workspaceRoot()
        val whitelist = LocalPathWhitelist.workspaceOnly(workspace)

        assertTrue(whitelist.canRead(File(workspace, "a.txt")))
        assertTrue(whitelist.canWrite(File(workspace, "a.txt")))
        assertTrue(whitelist.canAutoApproveWrite(File(workspace, "a.txt")))

        val outside = temporaryFolder.newFolder("outside")
        assertNull(whitelist.match(File(outside, "x.txt")))
        assertFalse(whitelist.canRead(File(outside, "x.txt")))
        assertFalse(whitelist.canAutoApproveWrite(File(outside, "x.txt")))
    }

    @Test
    fun externalRootWithoutAutoApprovalIsReachableButStillPrompts() {
        val workspace = workspaceRoot()
        val media = externalRoot("Download")
        val whitelist = LocalPathWhitelist(
            listOf(
                LocalPathRoot(LocalPathRootKind.WORKSPACE, workspace.absolutePath),
                LocalPathRoot(
                    LocalPathRootKind.EXTERNAL,
                    media.absolutePath,
                    autoApproveWrites = false,
                ),
            ),
        )

        val target = File(media, "report.txt")
        assertTrue("外部根内应可读写", whitelist.canRead(target))
        assertTrue(whitelist.canWrite(target))
        assertFalse("未开启自动批准时必须弹窗", whitelist.canAutoApproveWrite(target))
    }

    @Test
    fun externalRootCanOptIntoAutoApproval() {
        val workspace = workspaceRoot()
        val media = externalRoot("Documents")
        val whitelist = LocalPathWhitelist(
            listOf(
                LocalPathRoot(LocalPathRootKind.WORKSPACE, workspace.absolutePath),
                LocalPathRoot(
                    LocalPathRootKind.EXTERNAL,
                    media.absolutePath,
                    autoApproveWrites = true,
                ),
            ),
        )

        assertTrue(whitelist.canAutoApproveWrite(File(media, "notes.txt")))
        // Opting one root in must not widen any other path.
        val other = temporaryFolder.newFolder("Elsewhere")
        assertFalse(whitelist.canAutoApproveWrite(File(other, "notes.txt")))
    }

    @Test
    fun readOnlyRootRejectsWritesEvenWhenAutoApprovalIsOn() {
        val workspace = workspaceRoot()
        val pictures = externalRoot("Pictures")
        val whitelist = LocalPathWhitelist(
            listOf(
                LocalPathRoot(LocalPathRootKind.WORKSPACE, workspace.absolutePath),
                LocalPathRoot(
                    LocalPathRootKind.EXTERNAL,
                    pictures.absolutePath,
                    autoApproveWrites = true,
                    readOnly = true,
                ),
            ),
        )

        val target = File(pictures, "photo.jpg")
        assertTrue(whitelist.canRead(target))
        assertFalse(whitelist.canWrite(target))
        assertFalse(whitelist.canAutoApproveWrite(target))
    }

    @Test
    fun traversalCannotEscapeAnAuthorizedRoot() {
        val workspace = workspaceRoot()
        val media = externalRoot("Download")
        val whitelist = LocalPathWhitelist(
            listOf(
                LocalPathRoot(LocalPathRootKind.WORKSPACE, workspace.absolutePath),
                LocalPathRoot(LocalPathRootKind.EXTERNAL, media.absolutePath, autoApproveWrites = true),
            ),
        )

        // `..` resolves outside every authorized root, so it must not match.
        val escaped = File(media, "../outside.txt")
        assertNull(whitelist.match(escaped))
        assertFalse(whitelist.canAutoApproveWrite(escaped))
    }

    @Test
    fun nestedRootsAreRejectedAtConstruction() {
        val workspace = workspaceRoot()
        val nested = File(workspace, "inner")
        var failed = false
        try {
            LocalPathWhitelist(
                listOf(
                    LocalPathRoot(LocalPathRootKind.WORKSPACE, workspace.absolutePath),
                    LocalPathRoot(LocalPathRootKind.EXTERNAL, nested.absolutePath),
                ),
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("嵌套根必须被拒绝", failed)
    }

    @Test
    fun whitelistWithoutWorkspaceRootIsRejected() {
        val media = externalRoot("Download")
        var failed = false
        try {
            LocalPathWhitelist(
                listOf(LocalPathRoot(LocalPathRootKind.EXTERNAL, media.absolutePath)),
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("缺少工作区根必须被拒绝", failed)
    }
}
