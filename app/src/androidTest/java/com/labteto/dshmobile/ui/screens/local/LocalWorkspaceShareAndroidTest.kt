package com.labteto.dshmobile.ui.screens.local

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalWorkspaceShareAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharesRealSingleFileWithReadGrantAndOriginalName() {
        val root = File(context.filesDir, "local-harness/workspace")
        val folder = File(root, "share-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(folder, "报告.txt").apply { writeText("分享内容") }
            val intent = createLocalWorkspaceShareIntent(
                context, root.canonicalPath, listOf("${folder.name}/${file.name}"),
            )
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertNotNull(uri)
            assertEquals("content", uri!!.scheme)
            assertTrue(uri.toString().contains(file.name) || uri.toString().contains("%"))
            assertEquals("分享内容", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun sharesEverySelectedFileInOneMultipleShareIntent() {
        val root = File(context.filesDir, "local-harness/workspace")
        val folder = File(root, "share-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(folder, "one.txt").writeText("one")
            File(folder, "two.md").writeText("two")
            val intent = createLocalWorkspaceShareIntent(
                context, root.canonicalPath, listOf("${folder.name}/one.txt", "${folder.name}/two.md"),
            )
            assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
            assertEquals("text/*", intent.type)
            assertEquals(2, intent.clipData!!.itemCount)
            @Suppress("DEPRECATION")
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(2, uris.size)
            assertEquals(
                listOf("one", "two"),
                uris.map { uri ->
                    context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                },
            )
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun refusesWorkspaceNotOwnedByTheApp() {
        val root = File(context.filesDir, "local-harness/workspace")
        root.mkdirs()
        assertThrows(IllegalArgumentException::class.java) {
            createLocalWorkspaceShareIntent(context, context.cacheDir.absolutePath, listOf("fake.txt"))
        }
    }
}
