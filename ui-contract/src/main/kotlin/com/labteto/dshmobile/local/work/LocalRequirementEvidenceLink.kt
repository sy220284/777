package com.labteto.dshmobile.local.work

/** User-selected evidence reference. The link by itself does not assert a test or task passed. */
data class LocalRequirementEvidenceLink(
    val requirementIndex: Int,
    val requirement: String,
    val artifactPath: String,
    val artifactSequence: Long,
    val sourceCallId: String,
    val versionAtLink: String,
    val eventSequence: Long,
    /** SHA-256 of the actual current file, null when unreadable or removed. */
    val versionNow: String? = null,
)
