package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.toLocalChatProjectionState

import com.labteto.dshmobile.local.localAggregateChatStatePort

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatPersonaCoordinatorTest {
    private fun initialState() = MutableStateFlow(LocalHarnessState(
        sessionId = "persona-edit",
        loading = false,
        usageMode = LocalUsageMode.CHAT,
    ))

    @Test
    fun selectionCommitsDurableFactBeforePublishingRuntimeProjection() = runBlocking {
        val state = initialState()
        var commits = 0
        val coordinator = LocalChatPersonaCoordinator(
            state = localAggregateChatStatePort(state),
            loadPersona = { null },
            savePersona = { it },
            restorePersona = { _, _ -> error("成功提交不得回滚") },
            commitDomainState = { committed, _ ->
                assertTrue(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))
                assertEquals(PersonaProfile.DEFAULT_PERSONA_ID, state.value.chat.personaId)
                assertEquals("new", committed.chat.personaId)
                commits++
            },
            enqueueSnapshot = { true },
        )

        assertTrue(coordinator.selectNow(
            state.value.toLocalChatProjectionState(),
            PersonaProfile(id = "new"),
        ))
        assertEquals(1, commits)
        assertEquals("new", state.value.chat.personaId)
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))
    }

    @Test
    fun lateSelectionRollsBackPersonaWriteAndNeverCommitsDomainState() = runBlocking {
        for (mutation in listOf<(LocalHarnessState) -> LocalHarnessState>(
            { it.copy(chat = it.chat.copy(personaId = "newer")) },
            { it.copy(chat = it.chat.copy(galleryId = "new-gallery")) },
            { it.copy(usageMode = LocalUsageMode.WORK) },
            { it.copy(kernel = it.kernel.copy(running = true)) },
            { it.copy(sessionId = "other") },
        )) {
            val state = initialState()
            var restores = 0
            var commits = 0
            val coordinator = LocalChatPersonaCoordinator(
                state = localAggregateChatStatePort(state),
                loadPersona = { PersonaProfile(id = "old", name = "旧值") },
                savePersona = { profile -> state.value = mutation(state.value); profile },
                restorePersona = { _, _ -> restores++ },
                commitDomainState = { _, _ -> commits++ },
                enqueueSnapshot = { true },
            )
            assertFalse(coordinator.selectNow(
                state.value.toLocalChatProjectionState(),
                PersonaProfile(id = "old"),
            ))
            assertEquals(1, restores)
            assertEquals(0, commits)
            assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))
        }
    }

    @Test
    fun existingOwnerRejectsBothEditsBeforePersonaWrite() = runBlocking {
        val state = initialState()
        val coordinator = LocalChatPersonaCoordinator(
            state = localAggregateChatStatePort(state),
            loadPersona = { error("占用期间不得读取") },
            savePersona = { error("占用期间不得写入") },
            restorePersona = { _, _ -> error("占用期间不得回滚") },
            commitDomainState = { _, _ -> error("占用期间不得提交") },
            enqueueSnapshot = { true },
        )
        val owner = requireNotNull(LocalSessionRuntimeRegistry.tryAcquire(
            "persona-edit",
            LocalSessionRuntimeKind.AUTOMATION_CHAT,
        ))
        try {
            assertFalse(coordinator.selectNow(
                state.value.toLocalChatProjectionState(),
                PersonaProfile(),
            ))
            assertTrue(runCatching { coordinator.syncDefault(PersonaProfile()) }.isFailure)
        } finally {
            owner.close()
        }
    }

    @Test
    fun failedDomainCommitRestoresPersonaAndLeavesRuntimeProjectionUnchanged() = runBlocking {
        val state = initialState()
        var restored: PersonaProfile? = null
        val previous = PersonaProfile(id = "new", name = "旧人物")
        val coordinator = LocalChatPersonaCoordinator(
            state = localAggregateChatStatePort(state),
            loadPersona = { previous },
            savePersona = { it },
            restorePersona = { _, value -> restored = value },
            commitDomainState = { _, _ -> throw IOException("disk full") },
            enqueueSnapshot = { error("权威事件失败后不得写快照") },
        )

        val result = runCatching {
            coordinator.selectNow(
                state.value.toLocalChatProjectionState(),
                PersonaProfile(id = "new", name = "新人物"),
            )
        }
        assertTrue(result.isFailure)
        assertEquals(previous, restored)
        assertEquals(PersonaProfile.DEFAULT_PERSONA_ID, state.value.chat.personaId)
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))
    }

    @Test
    fun defaultSyncRejectsLateResultAndRollsBackItsStoreWrite() = runBlocking {
        val state = initialState()
        var restores = 0
        val coordinator = LocalChatPersonaCoordinator(
            state = localAggregateChatStatePort(state),
            loadPersona = { PersonaProfile() },
            savePersona = { profile ->
                state.value = state.value.copy(usageMode = LocalUsageMode.WORK)
                profile
            },
            restorePersona = { _, _ -> restores++ },
            commitDomainState = { _, _ -> error("过期状态不得提交") },
            enqueueSnapshot = { true },
        )

        assertTrue(runCatching { coordinator.syncDefault(PersonaProfile(name = "old")) }.isFailure)
        assertEquals(1, restores)
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("persona-edit"))
    }
}
