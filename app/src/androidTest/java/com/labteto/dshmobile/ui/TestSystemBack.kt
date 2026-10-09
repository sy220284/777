package com.labteto.dshmobile.ui

import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Inject a real system Back key synchronously without Espresso root selection.
 *
 * The adb shell "input keyevent" command can finish before its input dispatch.
 * Android 16 occasionally leaves Compose BackHandler assertions observing zero
 * callbacks despite a correct onBack action. sendKeyDownUpSync synchronizes
 * key injection with the instrumentation process.
 */
internal fun pressDeviceBack() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    instrumentation.waitForIdleSync()
}
