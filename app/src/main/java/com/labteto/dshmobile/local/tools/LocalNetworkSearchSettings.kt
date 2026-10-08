package com.labteto.dshmobile.local.tools

import android.content.Context
import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-local preference for built-in web search and page retrieval. */
@Singleton
internal class LocalNetworkSearchSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = LocalHarnessPreferences.from(context)
    private val enabledState = MutableStateFlow(isEnabled())
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        enabledState.value = value
    }

    private companion object {
        const val KEY_ENABLED = "network_search_enabled"
    }
}
