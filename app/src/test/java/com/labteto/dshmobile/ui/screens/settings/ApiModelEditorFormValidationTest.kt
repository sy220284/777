package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelEditorFormValidationTest {
    @Test fun emptyFormHighlightsEachMissingField() {
        val errors = validateApiModelEditorForm("", "", "", hasSavedCredential = false)
        assertFalse(errors.valid)
        assertTrue(errors.model!!.contains("模型"))
        assertTrue(errors.baseUrl!!.contains("接口"))
        assertTrue(errors.apiKey!!.contains("密钥"))
    }

    @Test fun existingCredentialCanBeKeptWithoutReenteringSecret() {
        val result = validateApiModelEditorForm("model", "https://example.com", "", true)
        assertTrue(result.valid)
        assertNull(result.apiKey)
    }

    @Test fun newProviderRequiresSecretButKeepsEnteredValues() {
        val missingKey = validateApiModelEditorForm("model", "https://example.com", "", false)
        assertFalse(missingKey.valid)
        assertNull(missingKey.model)
        assertNull(missingKey.baseUrl)
        assertTrue(missingKey.apiKey != null)
        assertTrue(validateApiModelEditorForm("model", "https://example.com", "secret", false).valid)
    }
}
