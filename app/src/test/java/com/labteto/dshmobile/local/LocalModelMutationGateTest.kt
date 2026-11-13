package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelMutationGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelMutationGateTest {
    @Test
    fun secondMutationCannotEnterBeforeFirstLeaves() = runTest {
        val gate = LocalModelMutationGate
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = async {
            gate.run {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val second = async {
            gate.run {
                secondEntered = true
            }
        }
        runCurrent()

        assertFalse(secondEntered)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(true, secondEntered)
    }

    @Test
    fun cancelledWaiterDoesNotPoisonFollowingMutation() = runTest {
        val gate = LocalModelMutationGate
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var cancelledWaiterEntered = false
        var followingEntered = false

        val first = async {
            gate.run {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val cancelled = async {
            gate.run {
                cancelledWaiterEntered = true
            }
        }
        runCurrent()
        cancelled.cancel()
        cancelled.join()

        val following = async {
            gate.run {
                followingEntered = true
            }
        }
        runCurrent()
        assertFalse(followingEntered)

        releaseFirst.complete(Unit)
        joinAll(first, following)

        assertFalse(cancelledWaiterEntered)
        assertTrue(followingEntered)
    }


    private data class RouteState(
        val profiles: Set<String>,
        val active: String?,
    )

    private class InterleavingFixture {
        var disk = RouteState(setOf("A", "B"), "A")
        var ui = disk
        var activeGateway = disk.active

        suspend fun commit(
            update: (RouteState) -> RouteState,
            fail: Boolean = false,
            beforePublish: (suspend () -> Unit)? = null,
        ) = LocalModelMutationGate.run {
            val next = update(disk)
            beforePublish?.invoke()
            if (fail) error("injected storage failure")
            disk = next
            activeGateway = next.active
            ui = next // the same gate is held until durable state and projection agree
        }

        suspend fun select(id: String) = LocalModelMutationGate.run {
            require(id in disk.profiles) { "deleted model" }
            ui = ui.copy(active = id)
            activeGateway = id
        }
    }

    @Test
    fun selectThenRemoveCannotRestoreDeletedProfile() = runTest {
        val fixture = InterleavingFixture()
        val selected = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val select = async {
            LocalModelMutationGate.run {
                fixture.ui = fixture.ui.copy(active = "B")
                selected.complete(Unit)
                release.await()
            }
        }
        selected.await()
        val remove = async {
            fixture.commit({ it.copy(profiles = setOf("A"), active = "A") })
        }
        runCurrent()
        assertEquals("B", fixture.ui.active)
        release.complete(Unit)
        select.await()
        remove.await()
        assertEquals(fixture.disk, fixture.ui)
        assertEquals("A", fixture.activeGateway)
    }

    @Test
    fun selectThenSavePublishesOnlyLatestDurableSnapshot() = runTest {
        val fixture = InterleavingFixture()
        fixture.select("B")
        val firstPublished = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            fixture.commit({ it.copy(profiles = it.profiles + "C", active = "C") },
                beforePublish = { firstPublished.complete(Unit); release.await() })
        }
        firstPublished.await()
        val second = async {
            fixture.commit({ it.copy(profiles = it.profiles + "D", active = "D") })
        }
        runCurrent()
        assertFalse("D" in fixture.disk.profiles)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(setOf("A", "B", "C", "D"), fixture.ui.profiles)
        assertEquals(fixture.disk, fixture.ui)
        assertEquals("D", fixture.activeGateway)
    }

    @Test
    fun removeThenSelectRejectsDeletedIdentity() = runTest {
        val fixture = InterleavingFixture()
        fixture.commit({ it.copy(profiles = setOf("A"), active = "A") })
        assertTrue(runCatching { fixture.select("B") }.isFailure)
        assertEquals(fixture.disk, fixture.ui)
        assertEquals("A", fixture.activeGateway)
    }

    @Test
    fun failedSecondMutationKeepsFirstSuccessfulProjection() = runTest {
        val fixture = InterleavingFixture()
        fixture.commit({ it.copy(profiles = it.profiles + "C", active = "C") })
        assertTrue(runCatching {
            fixture.commit({ it.copy(profiles = setOf("A"), active = "A") }, fail = true)
        }.isFailure)
        assertEquals(fixture.disk, fixture.ui)
        assertEquals("C", fixture.activeGateway)
    }

    @Test
    fun accountCatalogRefreshAndSelectionAreAtomicAgainstSave() = runTest {
        val fixture = InterleavingFixture()
        val syncEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sync = async {
            fixture.commit({ it.copy(profiles = it.profiles + "ChatGPT", active = "ChatGPT") },
                beforePublish = { syncEntered.complete(Unit); release.await() })
        }
        syncEntered.await()
        val save = async {
            fixture.commit({ it.copy(profiles = it.profiles + "D", active = "D") })
        }
        runCurrent()
        assertFalse("D" in fixture.ui.profiles)
        release.complete(Unit)
        sync.await()
        save.await()
        assertEquals(fixture.disk, fixture.ui)
        assertEquals("D", fixture.activeGateway)
    }

    @Test
    fun mutationStormNeverOverlapsCriticalSection() = runTest {
        val gate = LocalModelMutationGate
        var active = 0
        var maxActive = 0
        var completed = 0

        val jobs = (0 until 100).map {
            async {
                gate.run {
                    active += 1
                    maxActive = maxOf(maxActive, active)
                    delay(1)
                    completed += 1
                    active -= 1
                }
            }
        }
        jobs.forEach { it.await() }

        assertEquals(100, completed)
        assertEquals(1, maxActive)
        assertEquals(0, active)
    }
}
