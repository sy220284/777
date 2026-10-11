package com.labteto.dshmobile.local.files

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The App owns a versioned runtime guide; README.md is user/Agent-owned and is never overwritten.
 * Existing workspaces receive updated guidance without losing their custom scripts or notes.
 */
internal object LocalWorkspaceToolGuide {
    private const val GUIDE_ASSET = "workspace-tool-guide.md"
    private const val MANAGED_GUIDE = "777-runtime-guide.md"

    /** Returns true only when the user-facing README was created. */
    fun ensure(context: Context, workspace: File): Boolean {
        val root = workspace.canonicalFile.toPath()
        val directory = File(workspace, ".dsh/tools")
        val readme = File(directory, "README.md")
        val managedGuide = File(directory, MANAGED_GUIDE)
        // Reject symlink escapes before opening or replacing either file.
        if (!directory.canonicalFile.toPath().startsWith(root) ||
            !readme.canonicalFile.toPath().startsWith(root) ||
            !managedGuide.canonicalFile.toPath().startsWith(root) ||
            Files.isSymbolicLink(managedGuide.toPath())
        ) return false
        if (!directory.isDirectory && !directory.mkdirs()) return false

        val bytes = context.assets.open(GUIDE_ASSET).use { it.readBytes() }
        if (!managedGuide.isFile || !managedGuide.readBytes().contentEquals(bytes)) {
            val temp = File.createTempFile(".777-guide-", ".tmp", directory)
            try {
                temp.writeBytes(bytes)
                try {
                    Files.move(temp.toPath(), managedGuide.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), managedGuide.toPath(),
                        StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temp.delete()
            }
        }

        if (readme.exists() || !readme.createNewFile()) return false
        return try {
            readme.writeText(
                "# 工作区工具\n\n" +
                    "系统维护的最新工具与环境指南：\`.dsh/tools/$MANAGED_GUIDE\`。\n" +
                    "本 README 可自由补充项目专属脚本，不会被 777 覆盖。\n",
            )
            true
        } catch (error: Exception) {
            readme.delete()
            throw error
        }
    }
}
