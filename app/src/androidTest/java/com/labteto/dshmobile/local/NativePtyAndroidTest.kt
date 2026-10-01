package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.runtime.PersistentPipeTerminalProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePtyAndroidTest {
    @Test
    fun nativePtyProvidesControllingTtyAndResize() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-pty").apply {
            deleteRecursively()
            mkdirs()
        }
        val terminal = PersistentPipeTerminalProvider(defaultWorkingDirectory = root)

        assertTrue("APK 未加载 dshpty 原生库", terminal.nativePtyAvailable())

        val sessionId = terminal.open(
            command = listOf("/system/bin/sh", "-i"),
            workingDirectory = root.absolutePath,
        )
        try {
            assertTrue(
                "原生 PTY resize 失败",
                terminal.resize(sessionId, columns = 100, rows = 40),
            )
            terminal.write(
                sessionId,
                "if [ -t 0 ] && [ -t 1 ]; then echo DSH_TTY_OK; else echo DSH_TTY_FAIL; fi\nstty size\n",
            )

            val output = buildString {
                repeat(120) {
                    append(terminal.read(sessionId))
                    if (contains("DSH_TTY_OK") && Regex("""(?:^|\s)40\s+100(?:\s|$)""").containsMatchIn(this)) {
                        return@buildString
                    }
                    delay(25)
                }
            }

            assertTrue("子进程没有 controlling TTY：$output", output.contains("DSH_TTY_OK"))
            assertTrue(
                "PTY 行列调整没有传递给 stty：$output",
                Regex("""(?:^|\s)40\s+100(?:\s|$)""").containsMatchIn(output),
            )
        } finally {
            terminal.close(sessionId)
            root.deleteRecursively()
        }
    }
}
