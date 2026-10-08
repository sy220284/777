package com.labteto.dshmobile.local.interaction

import android.content.Context
import android.content.SharedPreferences
import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class LocalApprovalMode {
    DEFAULT,
    MANUAL,
    AUTO,
}

/**
 * Device-local authority for the global approval mode.
 *
 * The string mode is the only durable fact. The legacy boolean is consumed once during
 * migration and removed atomically with the new value. Fresh installs default to DEFAULT.
 */
@Singleton
class LocalApprovalPreferences internal constructor(
    private val preferences: SharedPreferences,
) {
    private val initialMode = readStoredMode()
    private val mode = MutableStateFlow(initialMode)
    private val autoEnabled = MutableStateFlow(initialMode == LocalApprovalMode.AUTO)

    internal val approvalMode: StateFlow<LocalApprovalMode> = mode.asStateFlow()
    /** Compatibility projection: true means unrestricted automatic approval is active. */
    internal val enabled: StateFlow<Boolean> = autoEnabled.asStateFlow()

    private val projectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    internal fun observeMode(publish: (Boolean) -> Unit): Job =
        projectionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            enabled.collect { publish(it) }
        }

    internal fun observeApprovalMode(publish: (LocalApprovalMode) -> Unit): Job =
        projectionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            approvalMode.collect { publish(it) }
        }

    @Inject
    internal constructor(@ApplicationContext context: Context) : this(
        preferences = LocalHarnessPreferences.from(context),
    )

    @Synchronized
    internal fun currentMode(legacySessionValue: Boolean = false): LocalApprovalMode {
        if (!preferences.contains(KEY_APPROVAL_MODE) || preferences.contains(KEY_SAFE_AUTO_APPROVAL)) {
            val migrated = when {
                preferences.contains(KEY_APPROVAL_MODE) -> readStoredMode()
                preferences.contains(KEY_SAFE_AUTO_APPROVAL) ->
                    if (preferences.getBoolean(KEY_SAFE_AUTO_APPROVAL, true)) {
                        LocalApprovalMode.AUTO
                    } else {
                        LocalApprovalMode.MANUAL
                    }
                legacySessionValue -> LocalApprovalMode.AUTO
                else -> LocalApprovalMode.DEFAULT
            }
            persistMode(migrated)
        }
        val resolved = readStoredMode()
        publish(resolved)
        return resolved
    }

    internal fun isSafeAutoApprovalEnabled(legacySessionValue: Boolean = false): Boolean =
        currentMode(legacySessionValue) == LocalApprovalMode.AUTO

    @Synchronized
    internal fun setApprovalMode(value: LocalApprovalMode) {
        persistMode(value)
        publish(value)
    }

    @Synchronized
    internal fun setSafeAutoApprovalEnabled(enabled: Boolean) {
        setApprovalMode(if (enabled) LocalApprovalMode.AUTO else LocalApprovalMode.MANUAL)
    }

    private fun readStoredMode(): LocalApprovalMode {
        val explicit = preferences.getString(KEY_APPROVAL_MODE, null)
            ?.let { raw ->
                LocalApprovalMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
        if (explicit != null) return explicit
        if (preferences.contains(KEY_SAFE_AUTO_APPROVAL)) {
            return if (preferences.getBoolean(KEY_SAFE_AUTO_APPROVAL, true)) {
                LocalApprovalMode.AUTO
            } else {
                LocalApprovalMode.MANUAL
            }
        }
        return LocalApprovalMode.DEFAULT
    }

    private fun persistMode(value: LocalApprovalMode) {
        preferences.edit()
            .putString(KEY_APPROVAL_MODE, value.name.lowercase())
            .remove(KEY_SAFE_AUTO_APPROVAL)
            .apply()
    }

    private fun publish(value: LocalApprovalMode) {
        mode.value = value
        autoEnabled.value = value == LocalApprovalMode.AUTO
    }

    private companion object {
        const val KEY_APPROVAL_MODE = "approval_mode"
        const val KEY_SAFE_AUTO_APPROVAL = "safe_auto_approval"
    }
}
