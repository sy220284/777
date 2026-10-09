package com.labteto.dshmobile.ui

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Inject system Back without Espresso root-view focus selection.
 * Compose dialogs and sheets may own a separate focused window on Android 16.
 */
internal fun pressDeviceBack() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK"),
    ).use { it.readBytes() }
    instrumentation.waitForIdleSync()
}
