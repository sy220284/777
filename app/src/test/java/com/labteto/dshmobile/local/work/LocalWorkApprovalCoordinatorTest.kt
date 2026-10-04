package com.labteto.dshmobile.local.work

import android.content.SharedPreferences
import com.labteto.dshmobile.local.*
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
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
        assertFalse(a.state.value.deviceApprovalLease)
        assertFalse(wait.isCompleted)

        assertTrue(a.interactions.answerApproval("current", false))
        // The UI projection may still show the request until the waiting coroutine resumes.
        f.approvals.enableAutoApprovalForPending("current")
        f.approvals.enableDeviceApprovalLease("current")
        assertFalse(f.preferences.isSafeAutoApprovalEnabled())
        assertFalse(a.state.value.deviceApprovalLease)
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
        assertTrue(a.state.value.deviceApprovalLease)
        assertFalse(b.state.value.deviceApprovalLease)
        assertFalse(f.preferences.isSafeAutoApprovalEnabled())

        f.runtime.activateSession("b")
        f.runtime.mutableState.value = f.runtime.state.value.copy(sessionId = "b")
        f.approvals.disableDeviceApprovalLease()
        assertTrue(a.state.value.deviceApprovalLease)
        f.runtime.activateSession("a")
        f.runtime.mutableState.value = f.runtime.state.value.copy(sessionId = "a", deviceApprovalLease = true)
        f.approvals.disableDeviceApprovalLease()
        assertFalse(a.state.value.deviceApprovalLease)
        assertFalse(f.runtime.state.value.deviceApprovalLease)
    }

    @Test fun ineligibleDeviceToolRemainsWaitingWithDurableRejection() = runTest {
        val f = Fixture()
        val a = f.binding("a")
        val wait = async { a.interactions.awaitApproval(approval("explicit")) }
        runCurrent()
        f.approvals.enableDeviceApprovalLease("explicit")
        assertFalse(a.state.value.deviceApprovalLease)
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
        assertFalse(f.runtime.state.value.deviceApprovalLease)
        assertFalse(wait.isCompleted)
        f.runtime.activateSession("a")
        f.approvals.enableDeviceApprovalLease("foreground")
        assertTrue(wait.await())
        assertTrue(f.runtime.state.value.deviceApprovalLease)
    }
}
