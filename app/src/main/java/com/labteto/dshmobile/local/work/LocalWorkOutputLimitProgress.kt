package com.labteto.dshmobile.local.work

/** Compare durable text, without a second retry ledger or a lifetime continuation cap. */
internal fun hasNewOutputLimitText(previous: String, next: String): Boolean {
    val text = next.trim()
    if (text.isEmpty()) return false
    val old = previous.trim()
    return old.isEmpty() || (text != old && !old.contains(text))
}

internal fun hasNewOutputLimitText(previous: List<String>, next: String): Boolean =
    next.isNotBlank() && previous.all { hasNewOutputLimitText(it, next) }
