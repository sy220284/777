package com.labteto.dshmobile.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Exercise the same AndroidX dispatch boundary used by an Activity's system Back.
 *
 * Shell `input keyevent KEYCODE_BACK` is focus-dependent: on heavily loaded CI
 * emulators it can target another window, yielding a false negative despite the
 * Compose BackHandler being correctly registered. The host dispatcher verifies
 * actual handler ownership without depending on external window focus.
 *
 * A real physical Back-key / predictive-gesture smoke test remains an environment
 * test, and must not be claimed by this isolated Compose regression.
 */
internal fun dispatchHostBack(dispatcher: OnBackPressedDispatcher) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync { dispatcher.onBackPressed() }
    instrumentation.waitForIdleSync()
}
