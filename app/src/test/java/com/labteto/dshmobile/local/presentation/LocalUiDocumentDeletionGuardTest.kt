package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.uicontract.DeleteUiDocumentCommand
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The R4 single-card tombstone must recheck active binding after disk IO. */
class LocalUiDocumentDeletionGuardTest {
    private val identity = UiSessionIdentity("session-a", "activation-a", 7, "profile-a", "project-a")
    private val source = UiDocumentSource("session-a", "message-a", 7, "profile-a", "project-a", false)
    private val command = DeleteUiDocumentCommand(identity, "doc-a", 2L, source)

    private fun admitted(
        current: UiSessionIdentity? = identity,
        work: Boolean = false,
        transitioning: Boolean = false,
        messages: Set<String> = setOf("message-a"),
        deleting: DeleteUiDocumentCommand = command,
    ) = mayDeleteUiDocumentForForeground(deleting, current, work, transitioning, messages)

    @Test fun acceptsOnlyTheExactForegroundMessageAndSource() {
        assertTrue(admitted())
        assertFalse(admitted(messages = emptySet()))
        assertFalse(admitted(messages = setOf("another-message")))
        assertFalse(admitted(transitioning = true))
        assertFalse(admitted(work = true))
    }

    @Test fun rejectsSessionSwitchEvenIfThePreviouslyReadCardStillExists() {
        assertFalse(admitted(current = null))
        assertFalse(admitted(current = identity.copy(sessionId = "session-b")))
        assertFalse(admitted(current = identity.copy(contextGeneration = 8)))
        assertFalse(admitted(current = identity.copy(profileId = "profile-b")))
        assertFalse(admitted(current = identity.copy(projectId = "project-b")))
        assertFalse(admitted(current = identity.copy(activationId = "activation-b")))
    }

    @Test fun rejectsAnExpiredDocumentSourceInTheSameSession() {
        assertFalse(admitted(deleting = command.copy(source = source.copy(contextGeneration = 6))))
        assertFalse(admitted(deleting = command.copy(source = source.copy(profileId = "another"))))
        assertFalse(admitted(deleting = command.copy(source = source.copy(projectId = "another"))))
        assertFalse(admitted(deleting = command.copy(source = source.copy(workMode = true))))
        assertFalse(admitted(deleting = command.copy(source = source.copy(messageId = "message-b"))))
    }
}
