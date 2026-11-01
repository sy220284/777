package com.labteto.dshmobile.ui.screens.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The draft belongs to the Settings ViewModel, not a disposable page composition. */
internal data class PersonalizationRulesEditState(
    val text: String = "",
    val open: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * One editor can survive a Settings sub-page change. Cancel invalidates its callback,
 * but does not pretend to cancel a write already accepted by the persistent owner.
 */
internal class PersonalizationRulesEditor {
    private val mutableState = MutableStateFlow(PersonalizationRulesEditState())
    val state: StateFlow<PersonalizationRulesEditState> = mutableState
    private var generation = 0L

    fun open(committedRules: String) {
        val current = mutableState.value
        if (current.open) return
        mutableState.value = current.copy(
            text = if (current.error != null) current.text else committedRules,
            open = true,
            saving = false,
        )
    }

    fun edit(text: String) {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(text = text.take(6_000), error = null)
    }

    fun cancel() {
        generation++
        mutableState.value = PersonalizationRulesEditState()
    }

    fun save(
        submit: (String, (Boolean, String?) -> Unit) -> Unit,
        onSaved: () -> Unit,
    ) {
        val current = mutableState.value
        if (!current.open || current.saving) return
        val request = ++generation
        mutableState.value = current.copy(saving = true, error = null)
        submit(current.text) { saved, error ->
            if (request != generation || !mutableState.value.open) return@submit
            mutableState.value = mutableState.value.copy(
                saving = false,
                open = !saved,
                error = if (saved) null else error ?: "设置已被后续修改替代，请确认后重新保存",
            )
            if (saved) onSaved()
        }
    }
}
