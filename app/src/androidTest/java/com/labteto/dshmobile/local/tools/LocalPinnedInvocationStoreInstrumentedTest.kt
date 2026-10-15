package com.labteto.dshmobile.local.tools

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Android noBackup storage rather than only testing token encoding. */
@RunWith(AndroidJUnit4::class)
class LocalPinnedInvocationStoreInstrumentedTest {
    @Test
    fun selectionsSurviveRecreationAndOnlyExplicitRemovalUnpinsThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "composer-invocation-${UUID.randomUUID()}")
        val wrapper = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = root
        }
        val github = LocalPinnedInvocation(LocalPinnedInvocation.Kind.CONNECTOR, "github")
        val review = LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "code-review")
        try {
            val first = LocalPinnedInvocationStore(wrapper)
            first.toggle(github)
            first.toggle(review)
            assertEquals(listOf(github, review), first.selected.value)

            val restarted = LocalPinnedInvocationStore(wrapper)
            assertEquals(listOf(github, review), restarted.selected.value)
            restarted.remove(github)
            val afterRemoval = LocalPinnedInvocationStore(wrapper)
            assertFalse(github in afterRemoval.selected.value)
            assertTrue(review in afterRemoval.selected.value)
        } finally {
            root.deleteRecursively()
        }
    }
}
