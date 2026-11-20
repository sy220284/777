package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Single application-wide opt-in, stored alongside other locally persisted Chat settings. */
internal interface LocalChatIntimacySettingsPort {
    fun initialEnabled(): Boolean
    /** Returns success only after the local preference has been durably committed. */
    fun configureEnabled(enabled: Boolean): Boolean
}

@Singleton
internal class LocalChatIntimacySettingsCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val state: LocalChatStatePort,
) : LocalChatIntimacySettingsPort {
    private val preferences = LocalHarnessPreferences.from(context)

    override fun initialEnabled(): Boolean =
        preferences.getBoolean(KEY_CHAT_INTIMACY_ENABLED, false)

    override fun configureEnabled(enabled: Boolean): Boolean {
        if (!preferences.edit().putBoolean(KEY_CHAT_INTIMACY_ENABLED, enabled).commit()) return false
        state.update { current ->
            current.copy(chat = current.chat.copy(chatIntimacyModeEnabled = enabled))
        }
        return true
    }

    private companion object {
        const val KEY_CHAT_INTIMACY_ENABLED = "chat_intimacy_mode_enabled"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocalChatIntimacySettingsModule {
    @Binds
    @Singleton
    abstract fun bindLocalChatIntimacySettingsPort(
        coordinator: LocalChatIntimacySettingsCoordinator,
    ): LocalChatIntimacySettingsPort
}
