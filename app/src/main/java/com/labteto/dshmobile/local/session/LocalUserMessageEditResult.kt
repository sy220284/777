package com.labteto.dshmobile.local.session

/** Result of a user-message edit, shared by Chat and Work UI without owning either domain. */
enum class LocalUserMessageEditResult {
    SENT,
    BUSY,
    UNAVAILABLE,
    WRONG_MODE,
    UNCONFIGURED,
    HISTORY_UNAVAILABLE,
    MESSAGE_MISSING,
    EMPTY,
    UNCHANGED,
    FAILED,
}
