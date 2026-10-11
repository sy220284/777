package com.labteto.dshmobile.local.files

import android.content.Context
import java.io.File

/**
 * Copies navigation-only documentation into the workspace.
 * Never overwrites an Agent/user-maintained file; the runtime registry is the source of truth.
 */
internal object LocalWorkspaceToolGuide {
    private const val GUIDE_ASSET = "workspace-tool-guide.md"

    fun ensure(context: Context, workspace: File): Boolean {
        val root = workspace.canonicalFile
        val directory = File(root, ".dsh/tools")
        val destination = File(directory, "README.md")
        // A preexisting workspace might contain symlinks. Never write outside its boundary.
        if (!destination.canonicalFile.toPath().startsWith(root.toPath())) return false
        if (destination.exists()) return false
        if (!directory.isDirectory && !directory.mkdirs()) return false
        // File.createNewFile() prevents overwriting even if another owner raced us.
        if (!destination.createNewFile()) return false
        return try {
            context.assets.open(GUIDE_ASSET).use { source ->
                destination.outputStream().use(source::copyTo)
            }
            true
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
}
