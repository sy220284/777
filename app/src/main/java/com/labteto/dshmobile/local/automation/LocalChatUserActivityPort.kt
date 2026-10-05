package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** Automation-owned cross-Feature port for Chat user-activity suppression/replanning. */
@Singleton
internal class LocalChatUserActivityPort @Inject constructor(
    private val scheduler: HarnessAutomationScheduler,
) {
    internal fun record(sessionId: String, userMessageAt: Long) {
        scheduler.onChatUserActivity(sessionId, userMessageAt)
    }
}
