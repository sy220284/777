package com.labteto.dshmobile.local.chat

/** Chat-owned policy for proactive Automation execution. */
internal data class LocalChatAutomationPolicy(
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = DEFAULT_QUIET_START_HOUR,
    val quietStartMinute: Int = DEFAULT_QUIET_START_MINUTE,
    val quietEndHour: Int = DEFAULT_QUIET_END_HOUR,
    val quietEndMinute: Int = DEFAULT_QUIET_END_MINUTE,
    val proactiveMinGapMinutes: Long = DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
    val proactiveMaxUnanswered: Int = DEFAULT_PROACTIVE_MAX_UNANSWERED,
    val minimumSilenceMinutes: Long? = null,
    val silenceReferenceAt: Long? = null,
    val bypassProactivePolicy: Boolean = false,
) {
    companion object {
        const val DEFAULT_QUIET_START_HOUR = 23
        const val DEFAULT_QUIET_START_MINUTE = 0
        const val DEFAULT_QUIET_END_HOUR = 7
        const val DEFAULT_QUIET_END_MINUTE = 0
        const val DEFAULT_PROACTIVE_MIN_GAP_MINUTES = 6L * 60L
        const val DEFAULT_PROACTIVE_MAX_UNANSWERED = 2
    }
}
