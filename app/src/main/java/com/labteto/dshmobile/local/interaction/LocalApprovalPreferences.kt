package com.labteto.dshmobile.local.interaction

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
    @Inject
    internal constructor(@ApplicationContext context: Context) : this(
        preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE),
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

    internal fun setSafeAutoApprovalEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SAFE_AUTO_APPROVAL, enabled).apply()
    }

    private companion object {
        const val KEY_SAFE_AUTO_APPROVAL = "safe_auto_approval"
    }
}
