package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRuntimeProjectionModelConfigurationTest {
    @Test
    fun configurationUpdatePreservesCurrentUnrelatedModelSettings() {
        val state = MutableStateFlow(LocalHarnessState())
        val projection = LocalRuntimeProjection(state)
        state.value = state.value.copy(modelState = state.value.modelState.copy(
            modelAttempts = 9,
            imageInputMode = LocalImageInputMode.NATIVE,
        ))
        val selected = LocalModelProfile("new-id", "new-model", "https://model.example")
        projection.projectModelConfiguration(
            true, selected.model, selected.baseUrl, listOf(selected), selected.id, null,
        )
        val result = state.value.modelState
        assertEquals(9, result.modelAttempts)
        assertEquals(LocalImageInputMode.NATIVE, result.imageInputMode)
        assertEquals(selected.id, result.modelSelection.activeProfileId)
        assertEquals(listOf(selected), result.modelSelection.profiles)
        assertEquals("new-model", result.model)
        assertNull(state.value.error)
    }

    @Test
    fun removingModelTrimsItsWorkerSelection() {
        val removed = LocalModelProfile("removed", "old", "https://old.example")
        val retained = LocalModelProfile("retained", "new", "https://new.example")
        val initial = LocalHarnessState()
        val state = MutableStateFlow(initial.copy(
            modelState = initial.modelState.copy(
                modelSelection = initial.modelState.modelSelection.copy(
                    profiles = listOf(removed, retained),
                    activeProfileId = removed.id,
                    workerProfileId = removed.id,
                ),
            ),
        ))
        LocalRuntimeProjection(state).projectModelConfiguration(
            true, retained.model, retained.baseUrl, listOf(retained), retained.id, null,
        )
        assertEquals(retained.id, state.value.modelState.modelSelection.activeProfileId)
        assertNull(state.value.modelState.modelSelection.workerProfileId)
    }
}
