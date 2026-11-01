package com.labteto.dshmobile.uicontract

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SettingsUiPortReceiptTest {
    @Test fun personalizationSubmissionNeverClaimsDurableApplication() {
        var queued = 0
        val port = fakePort { method ->
            when (method) {
                "configurePersonalization" -> { queued++; Unit }
                "configureRuntimeLimits" -> Unit
                else -> error("Unexpected port call: $method")
            }
        }
        assertEquals(
            SettingsUiReceipt.Submitted,
            port.submit(SettingsUiCommand.Personalization("rules", false, true)),
        )
        assertEquals(1, queued)
        assertEquals(
            SettingsUiReceipt.Applied,
            port.submit(SettingsUiCommand.RuntimeLimits(20, 30, 3, null)),
        )
    }

    @Test fun rejectedSubmissionIsNotMistakenForAppliedOrSubmitted() {
        val port = fakePort { throw IllegalStateException("channel closed") }
        val receipt = port.submit(SettingsUiCommand.Personalization("rules", true, false))
        assertTrue(receipt is SettingsUiReceipt.Failed)
        assertEquals("channel closed", (receipt as SettingsUiReceipt.Failed).message)
    }

    private fun fakePort(call: (String) -> Any?): SettingsUiPort =
        Proxy.newProxyInstance(
            SettingsUiPort::class.java.classLoader,
            arrayOf(SettingsUiPort::class.java),
        ) { _, method, _ -> call(method.name) } as SettingsUiPort
}
