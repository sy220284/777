package com.labteto.dshmobile.local.runtime

/**
 * Shared terminal execution outcome used by Feature execution ports and detached Automation.
 * Domain-specific payloads stay in their owning Feature; terminal semantics stay identical.
 */
internal enum class LocalExecutionStatus {
    DELIVERED,
    SKIPPED,
    BLOCKED,
    CANCELLED,
    FAILED,
}
