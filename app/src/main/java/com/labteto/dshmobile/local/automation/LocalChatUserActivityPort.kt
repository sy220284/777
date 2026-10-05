package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import com.labteto.dshmobile.local.chat.LocalChatUserActivityPort
import javax.inject.Inject
import javax.inject.Singleton

/** Automation adapter for the Chat-owned user-activity contract. */
@Singleton
internal class LocalAutomationChatUserActivityAdapter @Inject constructor(
    private val scheduler: HarnessAutomationScheduler,
) : LocalChatUserActivityPort {
    override fun record(sessionId: String, userMessageAt: Long) {
        scheduler.onChatUserActivity(sessionId, userMessageAt)
    }
}
