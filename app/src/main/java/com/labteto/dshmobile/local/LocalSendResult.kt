package com.labteto.dshmobile.local

internal enum class LocalSendDisposition {
    STARTED,
    QUEUED,
    REJECTED,
}

internal enum class LocalSendRejectReason {
    EMPTY,
    LOADING,
    UNCONFIGURED,
    SESSION_TRANSITION,
    QUEUE_FULL,
}

internal data class LocalSendResult(
    val disposition: LocalSendDisposition,
    val rejectReason: LocalSendRejectReason? = null,
    val message: String? = null,
) {
    val accepted: Boolean
        get() = disposition != LocalSendDisposition.REJECTED

    companion object {
        val Started = LocalSendResult(LocalSendDisposition.STARTED)
        val Queued = LocalSendResult(LocalSendDisposition.QUEUED)

        fun rejected(reason: LocalSendRejectReason, message: String): LocalSendResult =
            LocalSendResult(
                disposition = LocalSendDisposition.REJECTED,
                rejectReason = reason,
                message = message,
            )
    }
}

internal fun evaluateLocalSendAdmission(
    configured: Boolean,
    loading: Boolean,
    sessionTransitioning: Boolean,
    activeRun: Boolean,
    pendingCount: Int,
    pendingLimit: Int,
): LocalSendResult? {
    if (sessionTransitioning) {
        return LocalSendResult.rejected(
            LocalSendRejectReason.SESSION_TRANSITION,
            "会话正在切换，请稍后重试；当前输入已保留",
        )
    }
    if (loading) {
        return LocalSendResult.rejected(
            LocalSendRejectReason.LOADING,
            "会话仍在加载，请稍后重试；当前输入已保留",
        )
    }
    if (!configured) {
        return LocalSendResult.rejected(
            LocalSendRejectReason.UNCONFIGURED,
            "当前模型尚未配置，请先完成模型设置",
        )
    }
    if (activeRun && pendingCount >= pendingLimit) {
        return LocalSendResult.rejected(
            LocalSendRejectReason.QUEUE_FULL,
            "当前执行中的补充消息已达到 ${pendingLimit} 条上限；当前输入已保留",
        )
    }
    return null
}
