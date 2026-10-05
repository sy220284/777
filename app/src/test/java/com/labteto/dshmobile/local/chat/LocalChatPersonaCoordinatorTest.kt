package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.localAggregateProjectionPort
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatPersonaCoordinatorTest {
    private fun initialState() = MutableStateFlow(LocalHarnessState(
        sessionId = "persona-edit", loading = false, usageMode = LocalUsageMode.CHAT,
    ))

    @Test
    fun selectionOwnsSessionUntilSnapshotIsDurableAndRejectsCompetingRun() = runBlocking {
        val state = initialState()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = LocalChatPersonaCoordinator(
            state = localAggregateProjectionPort(state), savePersona = { it },
            persistNow = {
                reached.complete(Unit)
                release.await()
                true
            },
            enqueueSnapshot = { error("selection must await durable write") },
        )
        val selecting = async { coordinator.selectNow(state.value, PersonaProfile(id = "new")) }
        try {
            withTimeout(2_000) { reached.await() }
            assertFalse(selecting.isCompleted)
            assertTrue(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))
            assertTrue(LocalSessionRuntimeRegistry.tryAcquire(
                "persona-edit", LocalSessionRuntimeKind.FOREGROUND,
            ) == null)
            release.complete(Unit)
            assertTrue(withTimeout(2_000) { selecting.await() })
            assertEquals("new", state.value.chat.personaId)
            assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))
        } finally {
            release.complete(Unit)
            selecting.cancel()
        }
    }

    @Test
    fun lateSelectionCannotOverwriteChangedPersonaGalleryOrMode() = runBlocking {
        for (mutation in listOf<(LocalHarnessState) -> LocalHarnessState>(
            { it.copy(chat = it.chat.copy(personaId = "newer")) },
            { it.copy(chat = it.chat.copy(galleryId = "new-gallery")) },
            { it.copy(usageMode = LocalUsageMode.WORK) },
            { it.copy(kernel = it.kernel.copy(running = true)) },
            { it.copy(sessionId = "other") },
        )) {
            val state = initialState()
            var writes = 0
            val coordinator = LocalChatPersonaCoordinator(
                state = localAggregateProjectionPort(state),
                savePersona = { profile -> state.value = mutation(state.value); profile },
                persistNow = { writes++; true }, enqueueSnapshot = { true },
            )
            assertFalse(coordinator.selectNow(state.value, PersonaProfile(id = "old")))
            assertFalse(state.value.chat.personaId == "old")
            assertEquals(0, writes)
            assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))
        }
    }

    @Test
    fun existingOwnerRejectsBothEditsBeforePersonaWrite() = runBlocking {
        val state = initialState()
        val coordinator = LocalChatPersonaCoordinator(
            state = localAggregateProjectionPort(state), savePersona = { error("must not write while owned") },
            persistNow = { error("must not persist while owned") }, enqueueSnapshot = { true },
        )
        val owner = requireNotNull(LocalSessionRuntimeRegistry.tryAcquire(
            "persona-edit", LocalSessionRuntimeKind.AUTOMATION_CHAT,
        ))
        try {
            assertFalse(coordinator.selectNow(state.value, PersonaProfile()))
            assertTrue(runCatching { coordinator.syncDefault(PersonaProfile()) }.isFailure)
        } finally {
            owner.close()
        }
    }

    @Test
    fun defaultSyncRejectsLateResultAndPersistenceFailureCannotReportSuccess() = runBlocking {
        val state = initialState()
        val stale = LocalChatPersonaCoordinator(
            state = localAggregateProjectionPort(state),
            savePersona = { profile -> state.value = state.value.copy(usageMode = LocalUsageMode.WORK); profile },
            persistNow = { error("stale snapshot must not persist") }, enqueueSnapshot = { true },
        )
        assertTrue(runCatching { stale.syncDefault(PersonaProfile(name = "old")) }.isFailure)
        assertFalse(state.value.chat.chatPersona.name == "old")
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))

        val fresh = initialState()
        val failure = LocalChatPersonaCoordinator(
            state = localAggregateProjectionPort(fresh), savePersona = { it },
            persistNow = { throw java.io.IOException("disk full") }, enqueueSnapshot = { true },
        )
        assertTrue(runCatching { failure.selectNow(fresh.value, PersonaProfile(id = "new")) }.isFailure)
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))
    }
}
