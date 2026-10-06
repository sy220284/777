package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.persistence.LOCAL_HARNESS_PREFERENCES_NAME
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
    private val preferences = context.getSharedPreferences(LOCAL_HARNESS_PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun configureImageInputMode(mode: LocalImageInputMode) {
        preferences.edit().putString(LocalModelConfigContract.KEY_IMAGE_INPUT_MODE, mode.name).apply()
        runtimeStateStore.projection.setImageInputMode(mode)
    }

}
