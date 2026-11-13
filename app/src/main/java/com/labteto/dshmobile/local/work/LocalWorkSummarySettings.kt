package com.labteto.dshmobile.local.work

import android.content.Context
import android.content.SharedPreferences
import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Work owns this opt-in; Settings only exposes the existing preference. */
internal object LocalWorkSummarySettings {
    private const val KEY = "work_semantic_summary"
    private var preferences: SharedPreferences? = null
    private val enabled = MutableStateFlow(false)
    val state: StateFlow<Boolean> = enabled
    @Synchronized fun attach(context: Context) {
        if (preferences != null) return
        preferences = LocalHarnessPreferences.from(context.applicationContext).also {
            enabled.value = it.getBoolean(KEY, false)
        }
    }
    @Synchronized fun setEnabled(value: Boolean) {
        val saved = checkNotNull(preferences) { "工作摘要设置尚未加载" }
        val present = saved.contains(KEY)
        val before = saved.getBoolean(KEY, false)
        if (!saved.edit().putBoolean(KEY, value).commit()) {
            val rollback = saved.edit()
            if (present) rollback.putBoolean(KEY, before) else rollback.remove(KEY)
            rollback.commit()
            error("工作摘要设置保存失败，请重试。")
        }
        enabled.value = value
    }
}
