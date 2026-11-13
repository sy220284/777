package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountSummary
import org.junit.Assert.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptSettingsControllerTest {
    @Test
    fun transientModelDiscoveryFailureKeepsSignedInPlanProfiles() {
        val account = ChatGptAccountSummary(
            id = "account",
            clientId = "client",
            email = null,
            displayName = null,
            signedIn = true,
            sharingEnabled = true,
        )

        assertFalse(shouldRetireChatGptPlanProfiles(account))
    }

    @Test
    fun signedOutOrPlanDisabledAccountsRetirePlanProfiles() {
        val signedOut = ChatGptAccountSummary(
            id = "signed-out",
            clientId = "client",
            email = null,
            displayName = null,
            signedIn = false,
            sharingEnabled = false,
        )
        val planDisabled = signedOut.copy(
            id = "plan-disabled",
            signedIn = true,
        )

        assertTrue(shouldRetireChatGptPlanProfiles(null))
        assertTrue(shouldRetireChatGptPlanProfiles(signedOut))
        assertTrue(shouldRetireChatGptPlanProfiles(planDisabled))
    }
    @Test
    fun accountMutationFinishesAfterCallerCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var completed = false

        val job = launch {
            finishChatGptAccountMutation {
                entered.complete(Unit)
                release.await()
                completed = true
            }
        }

        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()

        assertTrue(completed)
    }
}
