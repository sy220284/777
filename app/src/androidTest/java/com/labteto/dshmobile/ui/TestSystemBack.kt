package com.labteto.dshmobile.ui

import android.os.ParcelFileDescriptor
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

/**
 * Real system key injection for dialog, window and IME interaction tests.
 * It intentionally remains separate from deterministic BackHandler tests.
 */
internal fun pressDeviceBack() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK"),
    ).use { it.readBytes() }
    instrumentation.waitForIdleSync()
}
