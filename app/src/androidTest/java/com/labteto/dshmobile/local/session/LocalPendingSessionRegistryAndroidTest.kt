package com.labteto.dshmobile.local.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.LocalUsageMode
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Pending editors remain resumable across owner recreation without entering the Session catalog. */
@RunWith(AndroidJUnit4::class)
class LocalPendingSessionRegistryAndroidTest {
    @Test
    fun pendingEditorIdentitySurvivesReopeningAndCanBeRetiredWhenCommitted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = LocalPendingSessionRegistry(context)
        val chatId = UUID.randomUUID().toString()
        val workId = UUID.randomUUID().toString()
        try {
            first.remember(chatId, LocalUsageMode.CHAT)
            first.remember(workId, LocalUsageMode.WORK)
            val restored = LocalPendingSessionRegistry(context)
            assertEquals(LocalUsageMode.CHAT, restored.modeFor(chatId))
            assertEquals(LocalUsageMode.WORK, restored.modeFor(workId))
            assertEquals(chatId, restored.latestFor(LocalUsageMode.CHAT))
            assertEquals(workId, restored.latestFor(LocalUsageMode.WORK))
            restored.forget(setOf(chatId))
            assertNull(first.modeFor(chatId))
            assertFalse(first.entries().containsKey(chatId))
            assertEquals(LocalUsageMode.WORK, first.modeFor(workId))
        } finally {
            first.forget(setOf(chatId, workId))
        }
    }
}
