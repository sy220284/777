package com.labteto.dshmobile.local

import android.net.Uri
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionFilesRuntime
import com.labteto.dshmobile.uicontract.AttachmentUiPort
import com.labteto.dshmobile.uicontract.ImportUiAttachmentCommand
import com.labteto.dshmobile.uicontract.UiAttachmentReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Platform data adaptation; the existing Session/File owner performs the only durable import. */
@Singleton
class LocalAttachmentUiPortComposition @Inject internal constructor(
    private val state: LocalRuntimeStateStore,
    private val files: LocalSessionFilesRuntime,
) : AttachmentUiPort {
    private fun current(command: ImportUiAttachmentCommand) = synchronized(state.foregroundRunHandle.lock) {
        !state.sessionTransitioning && state.uiBindingIdentity(state.state.value) == command.identity
    }

    override suspend fun import(command: ImportUiAttachmentCommand): UiAttachmentReceipt {
        if (!current(command)) return UiAttachmentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        return try {
            val imported = files.importAttachment(Uri.parse(command.contentUri))
            // A copied workspace file does not authorize adding it to a different active composer.
            if (!current(command)) UiAttachmentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            else UiAttachmentReceipt.Imported(imported)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiAttachmentReceipt.Failed(failure.message) }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalAttachmentUiPortBindings {
    @dagger.Binds @Singleton
    abstract fun attachments(implementation: LocalAttachmentUiPortComposition): AttachmentUiPort
}
