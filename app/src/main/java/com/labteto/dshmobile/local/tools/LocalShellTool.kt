package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.files.LocalWorkspace
import kotlinx.serialization.json.JsonObject

/**
 * Owns local shell timeout policy and foreground/background execution semantics.
 * Keeping this outside LocalHarnessEngine prevents process-lifecycle policy from leaking back into
 * the main agent state machine.
 */
internal object LocalShellTool {
    suspend fun execute(
        args: JsonObject,
        workspace: LocalWorkspace,
        jobs: LocalJobManager,
        ownerSessionId: String? = null,
    ): String {
        val command = args.string("command")
        val background = args.boolean("run_in_background", false)
        val timeout = args.int(
            "timeout_seconds",
            if (background) DEFAULT_BACKGROUND_TIMEOUT_SECONDS else DEFAULT_FOREGROUND_TIMEOUT_SECONDS,
        ).coerceIn(
            1,
            if (background) MAX_BACKGROUND_TIMEOUT_SECONDS else MAX_FOREGROUND_TIMEOUT_SECONDS,
        )

        return if (background) {
            jobs.start(
                label = "后台命令",
                expectedDurationMillis = timeout * 1_000L,
                ownerSessionId = ownerSessionId,
            ) { _, report ->
                workspace.shell(command, timeout, report, throwOnFailure = true)
            }
        } else {
            workspace.shell(command, timeout)
        }
    }

    private const val DEFAULT_FOREGROUND_TIMEOUT_SECONDS = 30
    private const val DEFAULT_BACKGROUND_TIMEOUT_SECONDS = 300
    private const val MAX_FOREGROUND_TIMEOUT_SECONDS = 120
    private const val MAX_BACKGROUND_TIMEOUT_SECONDS = 900
}
