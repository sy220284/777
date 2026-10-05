package com.labteto.dshmobile.local.work

import android.content.SharedPreferences
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LocalWorkApprovalCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Preferences {
        private val values = mutableMapOf<String, Boolean>()
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { values[args!![0] as String] = args[1] as Boolean; proxy }
                "apply" -> null
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val store = LocalApprovalPreferences(Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0] as String)
                "getBoolean" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences).apply { setSafeAutoApprovalEnabled(false) }
    }

    private inner class Fixture {
        val preferences = Preferences().store
        val events = LocalSessionEventLogRegistry(temporary.newFolder(), Json)
        val runtime = LocalRuntimeStateStore().apply {
            initialize(LocalHarnessState(loading = false, sessionId = "a", safeAutoApprovalEnabled = false))
        }
        val runs = LocalWorkRunRegistry(runtime)
        val approvals = LocalWorkApprovalCoordinator(preferences, events, runtime, runs)
        fun binding(id: String) = LocalWorkRunBinding(
            id, LocalHarnessState(loading = false, sessionId = id, safeAutoApprovalEnabled = false),
            emptyList(), events.get(id), null, 8, { it },
        ).also(runs::attach)
    }

    @Test fun preferenceFlowUpdatesExistingAndLateRunProjectionsWithoutCoordinatorFanOut() {
        val f = Fixture()
        val a = f.binding("a")
        f.preferences.setSafeAutoApprovalEnabled(true)

        assertTrue(f.preferences.enabled.value)
        assertTrue(f.runtime.state.value.safeAutoApprovalEnabled)
        assertTrue(a.state.value.safeAutoApprovalEnabled)

        val b = f.binding("b")
        assertTrue(b.state.value.safeAutoApprovalEnabled)
        f.preferences.setSafeAutoApprovalEnabled(false)
        assertFalse(a.state.value.safeAutoApprovalEnabled)
        assertFalse(b.state.value.safeAutoApprovalEnabled)
    }

    @Test fun detachedRunStopsObservingGlobalApprovalProjection() {
        val f = Fixture()
        val a = f.binding("a")
        assertSame(a, f.runs.detach("a"))

        f.preferences.setSafeAutoApprovalEnabled(true)
        assertTrue(f.runtime.state.value.safeAutoApprovalEnabled)
        assertFalse(a.state.value.safeAutoApprovalEnabled)

        f.runs.attach(a)
        assertTrue(a.state.value.safeAutoApprovalEnabled)
    }

    @Test fun runtimeInitializationProjectsCurrentApprovalAuthority() {
        val preferences = Preferences().store
        preferences.setSafeAutoApprovalEnabled(true)
        val runtime = LocalRuntimeStateStore()
        runtime.bindApprovalPreferences(preferences)
        runtime.initialize(LocalHarnessState(
            loading = false, sessionId = "fresh", safeAutoApprovalEnabled = false,
        ))
        assertTrue(runtime.state.value.safeAutoApprovalEnabled)
    }

    private fun approval(id: String, device: Boolean = false) = LocalApproval(
        id, "tool", "执行工具", "{}", "device_write", canApproveDeviceTurn = device,
    )

    @Test fun globalEnableResolvesEveryWaitingRunAndDisableUpdatesEveryProjection() = runTest {
        val f = Fixture()
        val a = f.binding("a")
        val b = f.binding("b")
        val first = async { a.interactions.awaitApproval(approval("first")) }
        val second = async { b.interactions.awaitApproval(approval("second")) }
        runCurrent()

        f.approvals.enableAutoApprovalForPending("first")

        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(f.preferences.isSafeAutoApprovalEnabled())
        assertTrue(a.state.value.safeAutoApprovalEnabled)
        assertTrue(b.state.value.safeAutoApprovalEnabled)
        assertTrue(f.runtime.state.value.safeAutoApprovalEnabled)
        assertNotNull(f.events.get("b").latest("approval/mode"))

        f.approvals.disableAutoApproval()
        assertFalse(f.preferences.isSafeAutoApprovalEnabled())
        assertFalse(a.state.value.safeAutoApprovalEnabled)
        assertFalse(b.state.value.safeAutoApprovalEnabled)
        assertFalse(f.runtime.state.value.safeAutoApprovalEnabled)
    }

    @Test fun staleOrAlreadyAnsweredClickCannotChangePolicyOrGrantDeviceLease() = runTest {
        val f = Fixture()
        val a = f.binding("a")
        val wait = async { a.interactions.awaitApproval(approval("current", device = true)) }
        runCurrent()
        f.approvals.enableAutoApprovalForPending("old")
        f.approvals.enableDeviceApprovalLease("old")
        assertFalse(f.preferences.isSafeAutoApprovalEnabled())
        assertFalse(a.state.value.work.deviceApprovalLease)
        assertFalse(wait.isCompleted)

        assertTrue(a.interactions.answerApproval("current", false))
        // The UI projection may still show the request until the waiting coroutine resumes.
        f.approvals.enableAutoApprovalForPending("current")
        f.approvals.enableDeviceApprovalLease("current")
        assertFalse(f.preferences.isSafeAutoApprovalEnabled())
        assertFalse(a.state.value.work.deviceApprovalLease)
        assertFalse(wait.await())
    }

    @Test fun deviceLeaseIsLimitedToItsRunAndRevocationPreservesOtherSession() = runTest {
        val f = Fixture()
        val a = f.binding("a")
        val b = f.binding("b")
        val wait = async { a.interactions.awaitApproval(approval("device", device = true)) }
        runCurrent()
        f.approvals.enableDeviceApprovalLease("device")
        assertTrue(wait.await())
        assertTrue(a.state.value.work.deviceApprovalLease)
        assertFalse(b.state.value.work.deviceApprovalLease)
        assertFalse(f.preferences.isSafeAutoApprovalEnabled())

        f.runtime.activateSession("b")
        f.runtime.mutableState.value = f.runtime.state.value.copy(sessionId = "b")
        f.approvals.disableDeviceApprovalLease(f.runtime.state.value.sessionId)
        assertTrue(a.state.value.work.deviceApprovalLease)
        f.runtime.activateSession("a")
        f.runtime.mutableState.value = f.runtime.state.value.copy(sessionId = "a", work = f.runtime.state.value.work.copy(deviceApprovalLease = true))
        f.approvals.disableDeviceApprovalLease(f.runtime.state.value.sessionId)
        assertFalse(a.state.value.work.deviceApprovalLease)
        assertFalse(f.runtime.state.value.work.deviceApprovalLease)
    }

    @Test fun staleVisibleRevokeCannotClearNewForegroundRunLeaseDuringTransition() = runTest {
        val f = Fixture()
        val a = f.binding("a")
        val b = f.binding("b")
        a.state.value = a.state.value.copy(work = a.state.value.work.copy(deviceApprovalLease = true))
        b.state.value = b.state.value.copy(work = b.state.value.work.copy(deviceApprovalLease = true))
        f.runtime.mutableState.value = f.runtime.state.value.copy(
            sessionId = "a",
            work = f.runtime.state.value.work.copy(deviceApprovalLease = true),
        )

        // Session identity advances before the visible projection switches away from A.
        f.runtime.activateSession("b")
        f.approvals.disableDeviceApprovalLease("a")

        assertFalse(a.state.value.work.deviceApprovalLease)
        assertFalse(f.runtime.state.value.work.deviceApprovalLease)
        assertTrue(b.state.value.work.deviceApprovalLease)
    }

    @Test fun ineligibleDeviceToolRemainsWaitingWithDurableRejection() = runTest {
        val f = Fixture()
        val a = f.binding("a")
        val wait = async { a.interactions.awaitApproval(approval("explicit")) }
        runCurrent()
        f.approvals.enableDeviceApprovalLease("explicit")
        assertFalse(a.state.value.work.deviceApprovalLease)
        assertFalse(wait.isCompleted)
        assertNotNull(a.eventLog.latest("approval/device-lease-rejected"))
        a.interactions.cancelAll()
        assertFalse(wait.await())
    }

    @Test fun foregroundWaitUsesSharedInteractionOwnerAndTransitionCannotGrantApproval() = runTest {
        val f = Fixture()
        val wait = async { f.runtime.foregroundInteractions.awaitApproval(approval("foreground", true)) }
        runCurrent()
        f.runtime.activateSession("next")
        f.approvals.enableDeviceApprovalLease("foreground")
        assertFalse(f.runtime.state.value.work.deviceApprovalLease)
        assertFalse(wait.isCompleted)
        f.runtime.activateSession("a")
        f.approvals.enableDeviceApprovalLease("foreground")
        assertTrue(wait.await())
        assertTrue(f.runtime.state.value.work.deviceApprovalLease)
    }
}
