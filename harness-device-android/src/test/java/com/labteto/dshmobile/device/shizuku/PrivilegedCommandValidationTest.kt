package com.labteto.dshmobile.device.shizuku

import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCommandValidationTest {
    @Test
    fun allowsOnlyStructuredPrivilegedCommands() {
        validatePrivilegedCommand(listOf("/system/bin/pm", "list", "packages"))
        validatePrivilegedCommand(listOf("/system/bin/am", "force-stop", "com.example.app"))
        validatePrivilegedCommand(listOf("/system/bin/settings", "put", "secure", "demo_key", "value with spaces"))
        validatePrivilegedCommand(listOf("/system/bin/dumpsys", "activity", "top"))
    }

    @Test
    fun rejectsShellAndCommandSmuggling() {
        val cases = listOf(
            listOf("/system/bin/sh", "-c", "id"),
            listOf("/system/bin/am", "force-stop", "com.example.app;id"),
            listOf("/system/bin/pm", "list", "packages", ";", "id"),
            listOf("/system/bin/dumpsys", "activity;id"),
            listOf("/system/bin/settings", "put", "global", "key\nnext", "value"),
        )
        cases.forEach { command ->
            assertTrue("expected rejection for $command", runCatching {
                validatePrivilegedCommand(command)
            }.isFailure)
        }
    }
}
