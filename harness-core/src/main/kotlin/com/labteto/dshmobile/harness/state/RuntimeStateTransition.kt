package com.labteto.dshmobile.harness.state

data class RuntimeStateTransition<T>(
    val value: T,
    val accepted: Boolean,
    val reason: String? = null,
)

fun interface RuntimeStateTransitionPolicy<T> {
    fun resolve(current: T, candidate: T): RuntimeStateTransition<T>
}

fun <T> acceptRuntimeStateTransition(candidate: T): RuntimeStateTransition<T> =
    RuntimeStateTransition(value = candidate, accepted = true)

fun <T> rejectRuntimeStateTransition(
    current: T,
    reason: String,
): RuntimeStateTransition<T> =
    RuntimeStateTransition(value = current, accepted = false, reason = reason)
