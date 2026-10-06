package com.labteto.dshmobile.local.interaction

import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single durable source of truth for global automatic approval.
 *
 * The setting is device-local and intentionally independent from conversation files. Historical
 * method/key names keep the old "safe" wording for compatibility, but true now means every
 * approval-gated operation is approved automatically. A legacy session flag may seed it once
 * during upgrade, but an explicit persisted choice always wins.
 */
@Singleton
class LocalApprovalPreferences internal constructor(
    private val preferences: SharedPreferences,
) {
    private val mode = MutableStateFlow(preferences.getBoolean(KEY_SAFE_AUTO_APPROVAL, true))
    internal val enabled: StateFlow<Boolean> = mode.asStateFlow()
    private val projectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Every UI/run projection observes the same authority; mode changes never enumerate copies. */
    internal fun observeMode(publish: (Boolean) -> Unit): Job =
        projectionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            enabled.collect { publish(it) }
        }

    @Inject
    internal constructor(@ApplicationContext context: Context) : this(
        preferences = LocalHarnessPreferences.from(context),
    )

    internal fun isSafeAutoApprovalEnabled(legacySessionValue: Boolean = false): Boolean {
        if (preferences.contains(KEY_SAFE_AUTO_APPROVAL)) {
            return preferences.getBoolean(KEY_SAFE_AUTO_APPROVAL, true)
        }
        if (legacySessionValue) {
            preferences.edit().putBoolean(KEY_SAFE_AUTO_APPROVAL, true).apply()
            return true
        }
        return true
    }

    @Synchronized
    internal fun setSafeAutoApprovalEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SAFE_AUTO_APPROVAL, enabled).apply()
        mode.value = enabled
    }

    private companion object {
        const val KEY_SAFE_AUTO_APPROVAL = "safe_auto_approval"
    }
}
