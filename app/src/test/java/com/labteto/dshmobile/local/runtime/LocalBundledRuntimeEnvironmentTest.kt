package com.labteto.dshmobile.local.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalBundledRuntimeEnvironmentTest {
    @Test fun nodeAndPythonLibrariesAreBothRetainedWithoutDuplicatingNativeRoots() {
        val python = mapOf(
            "LD_LIBRARY_PATH" to "/bundle/python/lib:/apk/native",
            "PYTHONHOME" to "/bundle/python/home",
            "HOME" to "/app/files",
        )
        val node = mapOf(
            "LD_LIBRARY_PATH" to "/bundle/node/lib:/apk/native",
            "NODE_EXTRA_CA_CERTS" to "/app/ca.pem",
        )
        val result = mergeBundledRuntimeEnvironments(listOf(python, node))
        assertEquals(listOf("/bundle/python/lib", "/apk/native", "/bundle/node/lib"),
            result.getValue("LD_LIBRARY_PATH").split(File.pathSeparator))
        assertEquals("/bundle/python/home", result.getValue("PYTHONHOME"))
        assertEquals("/app/ca.pem", result.getValue("NODE_EXTRA_CA_CERTS"))
        assertEquals("/app/files", result.getValue("HOME"))
    }

    @Test fun unpreparedRuntimesNeverInjectAnEmptyLibraryPath() {
        val result = mergeBundledRuntimeEnvironments(listOf(emptyMap(), mapOf("PATH" to "/bin")))
        assertFalse(result.containsKey("LD_LIBRARY_PATH"))
        assertEquals("/bin", result["PATH"])
    }
}
