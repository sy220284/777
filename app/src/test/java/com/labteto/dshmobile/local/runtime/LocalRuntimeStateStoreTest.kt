package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.LocalHarnessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class LocalRuntimeStateStoreTest {
    @Test
    fun initializePublishesOneSharedStateAndRejectsSecondOwner() {
        val store = LocalRuntimeStateStore()
        val initial = LocalHarnessState(sessionId = "session-a")

        val mutable = store.initialize(initial)

        assertSame(mutable, store.mutableState)
        assertEquals("session-a", store.state.value.sessionId)

        try {
            store.initialize(LocalHarnessState(sessionId = "session-b"))
            fail("第二个运行状态所有者不应重新初始化共享 Store")
        } catch (_: IllegalStateException) {
            assertEquals("session-a", store.state.value.sessionId)
        }
    }
}
