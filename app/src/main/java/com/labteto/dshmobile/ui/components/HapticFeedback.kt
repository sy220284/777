package com.labteto.dshmobile.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.roundToInt

/** Map a finite slider value to a semantic detent. Zero segments disables feedback. */
internal fun hapticTickIndex(value: Float, start: Float, end: Float, segments: Int): Int {
    if (segments <= 0 || !value.isFinite() || !start.isFinite() || !end.isFinite() || end <= start) return 0
    return (((value - start) / (end - start)).coerceIn(0f, 1f) * segments).roundToInt()
}

/** Reusable, system-aware light pulse with rate limiting for held/dragged controls. */
@Composable
fun rememberHapticPulse(minIntervalMillis: Long = 45L): () -> Unit {
    val haptics = LocalHapticFeedback.current
    val previous = remember { mutableLongStateOf(-1L) }
    return remember(haptics, minIntervalMillis) {
        {
            val now = SystemClock.uptimeMillis()
            if (previous.longValue < 0L || now - previous.longValue >= minIntervalMillis) {
                previous.longValue = now
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
}

/** Called from user gestures, never from recomposition or external model synchronization. */
@Composable
fun rememberHapticTickFeedback(initialIndex: Int): (Int) -> Unit {
    val pulse = rememberHapticPulse()
    val previous = remember { mutableIntStateOf(initialIndex) }
    LaunchedEffect(initialIndex) { previous.intValue = initialIndex }
    return remember(pulse) {
        { nextIndex: Int ->
            if (nextIndex != previous.intValue) {
                previous.intValue = nextIndex
                pulse()
            }
        }
    }
}
