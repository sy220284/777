package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.observability.sanitizeDiagnosticText

/**
 * The review model is an external recipient. Mask common credentials before composing
 * the request, while the stored evidence digest stays bound to the original Git bytes.
 * Entire private-key blocks cannot be safely reviewed as snippets: require their removal.
 */
internal fun prepareOutboundReviewPatch(patch: String): String {
    require(patch.length <= 120_000) { "审查范围超过上限" }
    require(!Regex("-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----", RegexOption.IGNORE_CASE)
        .containsMatchIn(patch)) {
        "Git 差异包含私钥材料。请先移除私钥，再将变更提交模型审核。"
    }
    return sanitizeDiagnosticText(patch)
}
