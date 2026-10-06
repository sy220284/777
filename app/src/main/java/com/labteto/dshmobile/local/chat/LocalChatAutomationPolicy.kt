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
    fun requireValid() {
        require(quietStartHour in 0..23 && quietEndHour in 0..23) { "免打扰小时无效" }
        require(quietStartMinute in 0..59 && quietEndMinute in 0..59) { "免打扰分钟无效" }
        require(proactiveMinGapMinutes >= MIN_PROACTIVE_GAP_MINUTES) { "主动互动最低间隔至少 1 小时" }
        require(proactiveMaxUnanswered in MIN_PROACTIVE_MAX_UNANSWERED..MAX_PROACTIVE_MAX_UNANSWERED) {
            "连续未回复上限必须为 1 到 5 次"
        }
        minimumSilenceMinutes?.let {
            require(it >= MIN_SILENCE_MINUTES) { "聊天沉默触发最短为 1 小时" }
        }
    }

    companion object {
        const val DEFAULT_QUIET_START_HOUR = 23
        const val DEFAULT_QUIET_START_MINUTE = 0
        const val DEFAULT_QUIET_END_HOUR = 7
        const val DEFAULT_QUIET_END_MINUTE = 0
        const val DEFAULT_PROACTIVE_MIN_GAP_MINUTES = 6L * 60L
        const val DEFAULT_PROACTIVE_MAX_UNANSWERED = 2

        const val MIN_PROACTIVE_GAP_MINUTES = 60L
        const val MIN_PROACTIVE_MAX_UNANSWERED = 1
        const val MAX_PROACTIVE_MAX_UNANSWERED = 5
        const val MIN_SILENCE_MINUTES = 60L

        fun normalizeProactiveMinGapMinutes(value: Long): Long =
            value.coerceAtLeast(MIN_PROACTIVE_GAP_MINUTES)

        fun normalizeProactiveMaxUnanswered(value: Int): Int =
            value.coerceIn(MIN_PROACTIVE_MAX_UNANSWERED, MAX_PROACTIVE_MAX_UNANSWERED)

        fun normalizeSilenceMinutes(value: Long): Long =
            value.coerceAtLeast(MIN_SILENCE_MINUTES)
    }
}
