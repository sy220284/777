package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import android.content.Context
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Model-owned user settings that affect request construction and capability exposure. */
@Singleton
class LocalModelSettingsCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    private val preferences = LocalHarnessPreferences.from(context)

    fun configureImageInputMode(mode: LocalImageInputMode) {
        preferences.edit().putString(LocalModelConfigContract.KEY_IMAGE_INPUT_MODE, mode.name).apply()
        runtimeStateStore.projection.setImageInputMode(mode)
    }

}
