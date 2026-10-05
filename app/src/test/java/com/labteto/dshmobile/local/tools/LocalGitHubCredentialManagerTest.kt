package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.interop.github.GitHubConnectorStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGitHubCredentialManagerTest {
    @Test
    fun validatedCredentialIsSavedOnlyAfterValidationSucceeds() = runTest {
        val calls = mutableListOf<String>()
        val status = persistValidatedGitHubCredential(
            token = "token-value",
            validate = { token ->
                calls += "validate:$token"
                GitHubConnectorStatus(configured = true, login = "tester")
            },
            save = { token -> calls += "save:$token" },
        )

        assertTrue(status.configured)
        assertEquals("tester", status.login)
        assertEquals(listOf("validate:token-value", "save:token-value"), calls)
    }

    @Test
    fun failedValidationDoesNotOverwriteStoredCredential() = runTest {
        var saved = false

        val failure = runCatching {
            persistValidatedGitHubCredential(
                token = "bad-token",
                validate = { error("invalid credential") },
                save = { saved = true },
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(saved)
    }
}
