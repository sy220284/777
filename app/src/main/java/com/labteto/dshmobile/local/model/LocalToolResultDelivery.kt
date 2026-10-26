package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.observability.AppLog
import kotlinx.coroutines.CancellationException

/** Delivery fails after execution: settle the original call, never replay its external effect. */
internal data class LocalToolResultDelivery(
    val content: String,
    val images: List<LocalImportedAttachment> = emptyList(),
    val failed: Boolean = false,
)

internal suspend fun deliverLocalToolResult(
    retain: () -> String,
    materialize: suspend () -> List<LocalImportedAttachment>,
): LocalToolResultDelivery {
    var retained: String? = null
    var phase = "retain"
    return try {
        val preview = retain()
        retained = preview
        phase = "materialize"
        LocalToolResultDelivery(preview, materialize())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // The tool may already have run. Record the real failure phase/cause for diagnosis
        // without repeating it or incorrectly assuming the disk is full.
        AppLog.failure("ToolResultDelivery", "deliver_tool_result_$"+"phase", error)
        LocalToolResultDelivery(
            content = (retained?.plus("\n") ?: "完整结果未能保存，本次结果不可回查。\n") +
                "工具已执行，但本机结果保存或图片导入失败。请检查存储/附件限制；禁止自动重放本次调用。",
            failed = true,
        )
    }
}
