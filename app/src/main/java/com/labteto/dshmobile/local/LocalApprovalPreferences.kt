package com.labteto.dshmobile.local

import android.content.SharedPreferences

/**
 * Single durable source of truth for safe automatic approval.
 *
 * The setting is device-local and intentionally independent from conversation files. A legacy
 * session flag may seed it once during upgrade, but an explicit persisted choice always wins.
 */
internal class LocalApprovalPreferences(
    private val preferences: SharedPreferences,
) {
    fun isSafeAutoApprovalEnabled(legacySessionValue: Boolean = false): Boolean {
        if (preferences.contains(KEY_SAFE_AUTO_APPROVAL)) {
            return preferences.getBoolean(KEY_SAFE_AUTO_APPROVAL, true)
        }
        if (legacySessionValue) {
            preferences.edit().putBoolean(KEY_SAFE_AUTO_APPROVAL, true).apply()
            return true
        }
        return true
    }

    fun setSafeAutoApprovalEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SAFE_AUTO_APPROVAL, enabled).apply()
    }

    private companion object {
        const val KEY_SAFE_AUTO_APPROVAL = "safe_auto_approval"
    }
}
