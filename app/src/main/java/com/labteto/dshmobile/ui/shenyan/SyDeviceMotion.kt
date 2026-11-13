package com.labteto.dshmobile.ui.shenyan

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Android's animator duration preference is user authority over V5 motion.
 * Subscribe to setting changes to avoid a stale cached preference after returning from Settings.
 */
@Composable
fun rememberSyReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun isReduced(): Boolean = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f
    }.getOrDefault(false)
    var reduced by remember(resolver) { mutableStateOf(isReduced()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = isReduced()
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        resolver.registerContentObserver(uri, false, observer)
        reduced = isReduced()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
