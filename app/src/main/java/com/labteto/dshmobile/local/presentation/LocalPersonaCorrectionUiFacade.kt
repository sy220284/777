package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalConversationUiPortComposition
import com.labteto.dshmobile.local.chat.LocalChatPersonaCorrectionCoordinator
import com.labteto.dshmobile.uicontract.PersonaCorrectionUiPort
import com.labteto.dshmobile.uicontract.UiPersonaCorrectionReceipt
import com.labteto.dshmobile.uicontract.UiPersonaCorrectionUndo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Real Chat Feature delegate; the Legacy Shell is only a thin UI adapter. */
@Singleton
internal class LocalPersonaCorrectionUiFacade @Inject constructor(
    private val composition: LocalConversationUiPortComposition,
    private val chatOwner: LocalChatPersonaCorrectionCoordinator,
) : PersonaCorrectionUiPort {
    override suspend fun undo(command: UiPersonaCorrectionUndo): UiPersonaCorrectionReceipt {
        // A notice alone cannot authorize undo after chat activation changes.
        if (composition.identitySnapshot() != command.identity)
            return UiPersonaCorrectionReceipt.Stale
        return try {
            val applied = withContext(Dispatchers.IO) {
                chatOwner.undo(
                    command.noticeId, command.personaId, command.correction,
                    expectedSessionId = command.identity.sessionId,
                )
            }
            if (applied) UiPersonaCorrectionReceipt.Undone
            else UiPersonaCorrectionReceipt.Stale
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            UiPersonaCorrectionReceipt.Failed(failure.javaClass.simpleName)
        }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalPersonaCorrectionUiBindings {
    @dagger.Binds @Singleton
    abstract fun correction(implementation: LocalPersonaCorrectionUiFacade): PersonaCorrectionUiPort
}
