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
    fun globalSaveCannotOverwriteAnotherConversationModelChoice() {
        val a = LocalModelProfile("route-a", "model-a", "https://a.example")
        val b = LocalModelProfile("route-b", "model-b", "https://b.example")
        val initial = LocalHarnessState(
            loading = false,
            sessionId = "session-b",
        )
        val state = MutableStateFlow(initial.copy(
            modelState = initial.modelState.copy(
                configured = true,
                model = b.model,
                baseUrl = b.baseUrl,
                modelSelection = initial.modelState.modelSelection.copy(
                    profiles = listOf(a, b),
                    activeProfileId = b.id,
                ),
            ),
        ))
        val projection = LocalRuntimeProjection(state)
        projection.projectModelConfiguration(
            true, a.model, a.baseUrl, listOf(a, b), a.id, null,
            preferredSessionId = "session-a",
        )
        assertEquals(b.id, state.value.modelState.modelSelection.activeProfileId)
        assertEquals(b.model, state.value.modelState.model)
        assertEquals(b.baseUrl, state.value.modelState.baseUrl)
        projection.projectModelConfiguration(
            true, a.model, a.baseUrl, listOf(a, b), a.id, null,
            preferredSessionId = "session-b",
        )
        assertEquals(a.id, state.value.modelState.modelSelection.activeProfileId)
        assertEquals(a.model, state.value.modelState.model)
    }

    @Test
    fun backgroundSyncRetainsForegroundChoiceUnlessItIsRemoved() {
        val a = LocalModelProfile("route-a", "model-a", "https://a.example")
        val b = LocalModelProfile("route-b", "model-b", "https://b.example")
        val initial = LocalHarnessState(
            loading = false,
            sessionId = "session-b",
        )
        val state = MutableStateFlow(initial.copy(
            modelState = initial.modelState.copy(
                configured = true,
                model = b.model,
                baseUrl = b.baseUrl,
                modelSelection = initial.modelState.modelSelection.copy(
                    profiles = listOf(a, b),
                    activeProfileId = b.id,
                ),
            ),
        ))
        val projection = LocalRuntimeProjection(state)
        projection.projectModelConfiguration(
            true, a.model, a.baseUrl, listOf(a, b), a.id, null,
        )
        assertEquals(b.id, state.value.modelState.modelSelection.activeProfileId)
        projection.projectModelConfiguration(
            true, a.model, a.baseUrl, listOf(a), a.id, null,
        )
        assertEquals(a.id, state.value.modelState.modelSelection.activeProfileId)
        assertEquals(a.model, state.value.modelState.model)
        assertEquals(a.baseUrl, state.value.modelState.baseUrl)
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
