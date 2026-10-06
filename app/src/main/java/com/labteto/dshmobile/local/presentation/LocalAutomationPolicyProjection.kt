package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.LocalChatAutomationPolicy

/** UI-facing projection of Chat-owned automation policy defaults. */
internal data class LocalChatAutomationPolicyDefaults(
    val quietStartHour: Int,
    val quietStartMinute: Int,
    val quietEndHour: Int,
    val quietEndMinute: Int,
    val proactiveMinGapMinutes: Long,
    val proactiveMaxUnanswered: Int,
)

internal object LocalAutomationPolicyProjection {
    val defaults = LocalChatAutomationPolicyDefaults(
        quietStartHour = LocalChatAutomationPolicy.DEFAULT_QUIET_START_HOUR,
        quietStartMinute = LocalChatAutomationPolicy.DEFAULT_QUIET_START_MINUTE,
        quietEndHour = LocalChatAutomationPolicy.DEFAULT_QUIET_END_HOUR,
        quietEndMinute = LocalChatAutomationPolicy.DEFAULT_QUIET_END_MINUTE,
        proactiveMinGapMinutes = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
        proactiveMaxUnanswered = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MAX_UNANSWERED,
    )

    val minimumSilenceMinutes: Long
        get() = LocalChatAutomationPolicy.MIN_SILENCE_MINUTES
}
