package com.labteto.dshmobile.local.files

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDownloadsPublisherAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val downloads get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test
    fun publishesChineseNamedDeliverableReusesIdenticalBytesAndKeepsNewVersion() {
        val workspaceRoot = File(context.cacheDir, "downloads-test-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(workspaceRoot, "任务/成果-${UUID.randomUUID()}.txt")
        source.parentFile!!.mkdirs()
        val publisher = LocalDownloadsPublisher(context, LocalWorkspace(workspaceRoot))
        try {
            source.writeText("第一版成果")
            val first = publisher.publish("任务/${source.name}")
            assertTrue(first.startsWith("Download/神言神语/"))
            assertEquals("第一版成果", readPublished(first))
            assertEquals(first, publisher.publish("任务/${source.name}"))
            assertEquals(1, countPublished(source.name.substringBeforeLast('.')))

            source.writeText("第二版成果")
            val second = publisher.publish("任务/${source.name}")
            assertNotEquals(first, second)
            assertEquals("第二版成果", readPublished(second))
            assertEquals(2, countPublished(source.name.substringBeforeLast('.')))
        } finally {
            removePublished(source.name.substringBeforeLast('.'))
            workspaceRoot.deleteRecursively()
        }
    }

    @Test
    fun preservesFinalDeliveryProjectFoldersUnderChineseDownloads() {
        val workspaceRoot = File(context.cacheDir, "downloads-project-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(workspaceRoot, "交付成果/项目示例/图片/插图-${UUID.randomUUID()}.svg")
        file.parentFile!!.mkdirs()
        try {
            file.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
            val location = LocalDownloadsPublisher(context, LocalWorkspace(workspaceRoot))
                .publish("交付成果/项目示例/图片/${file.name}")
            assertTrue(location.startsWith("Download/神言神语/项目示例/图片/"))
            assertEquals(file.readText(), readPublished(location))
        } finally {
            removePublished(file.name.substringBeforeLast('.'), "Download/神言神语/项目示例/图片/")
            workspaceRoot.deleteRecursively()
        }
    }

    @Test
    fun refusesToPublishWorkspaceParentPath() {
        val root = File(context.cacheDir, "downloads-invalid-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            assertThrows(FileNotFoundException::class.java) {
                LocalDownloadsPublisher(context, LocalWorkspace(root)).publish("../not-in-workspace.txt")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun matchingIds(
        prefix: String,
        directory: String = "Download/神言神语/",
    ): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        context.contentResolver.query(downloads,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            selection, arrayOf(directory), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.startsWith(prefix)) result += cursor.getLong(0) to name
            }
        }
        return result
    }

    private fun countPublished(prefix: String): Int = matchingIds(prefix).size

    private fun readPublished(path: String): String {
        val name = path.substringAfterLast('/')
        val directory = path.substringBeforeLast('/') + "/"
        val id = matchingIds(name, directory).firstOrNull { it.second == name }?.first
            ?: error("成果没有发布到公共下载目录：$path")
        return context.contentResolver.openInputStream(ContentUris.withAppendedId(downloads, id))!!
            .bufferedReader().use { it.readText() }
    }

    private fun removePublished(prefix: String, directory: String = "Download/神言神语/") {
        matchingIds(prefix, directory).forEach { (id, _) ->
            context.contentResolver.delete(ContentUris.withAppendedId(downloads, id), null, null)
        }
    }
}
