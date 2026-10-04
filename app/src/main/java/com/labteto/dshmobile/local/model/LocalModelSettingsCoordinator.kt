package com.labteto.dshmobile.local.model

import android.content.Context
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.update

/** Model-owned user settings that affect request construction and capability exposure. */
@Singleton
class LocalModelSettingsCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)

    fun configureImageInputMode(mode: LocalImageInputMode) {
        preferences.edit().putString(KEY_IMAGE_INPUT_MODE, mode.name).apply()
        runtimeStateStore.mutableState.update {
            it.copy(modelState = it.modelState.copy(imageInputMode = mode))
        }
    }

    companion object {
        const val KEY_IMAGE_INPUT_MODE = "image_input_mode"
    }
}
