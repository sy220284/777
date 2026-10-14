package com.labteto.dshmobile.local.usage

fun saturatingUsageAdd(left: Long, right: Long): Long {
    val safeLeft = left.coerceAtLeast(0L)
    val safeRight = right.coerceAtLeast(0L)
    return if (safeLeft > Long.MAX_VALUE - safeRight) Long.MAX_VALUE else safeLeft + safeRight
}

