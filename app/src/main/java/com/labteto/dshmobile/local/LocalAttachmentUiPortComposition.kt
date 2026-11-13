package com.labteto.dshmobile.local

import android.net.Uri
import android.content.Context
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import com.labteto.dshmobile.uicontract.UiAttachmentMedia
import com.labteto.dshmobile.uicontract.uiAttachmentMediaKind
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionFilesRuntime
import com.labteto.dshmobile.uicontract.AttachmentUiPort
import com.labteto.dshmobile.uicontract.ImportUiAttachmentCommand
import com.labteto.dshmobile.uicontract.InspectUiAttachmentCommand
import com.labteto.dshmobile.uicontract.UiAttachmentInspectionReceipt
import com.labteto.dshmobile.uicontract.UiAttachmentReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** URI admission is a pure, unit-testable boundary; Android still enforces provider grants. */
internal fun isSupportedPickerAttachmentUri(scheme: String?, authority: String?): Boolean =
    scheme == "content" && !authority.isNullOrBlank()

internal fun attachmentImportFailureReceipt(error: Exception): UiAttachmentReceipt =
    if (error is SecurityException) UiAttachmentReceipt.GrantRevoked
    else UiAttachmentReceipt.Failed("附件导入失败，请重新选择文件并重试")

/** Platform data adaptation; the existing Session/File owner performs the only durable import. */
@Singleton
class LocalAttachmentUiPortComposition @Inject internal constructor(
    private val state: LocalRuntimeStateStore,
    private val files: LocalSessionFilesRuntime,
    @ApplicationContext private val context: Context,
) : AttachmentUiPort {
    private fun current(command: ImportUiAttachmentCommand) = synchronized(state.foregroundRunHandle.lock) {
        !state.sessionTransitioning && state.uiBindingIdentity(state.state.value) == command.identity
    }

    override suspend fun inspect(command: InspectUiAttachmentCommand): UiAttachmentInspectionReceipt {
        val request = ImportUiAttachmentCommand(command.identity, "")
        if (!current(request))
            return UiAttachmentInspectionReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        val result = files.inspectAttachment(command.attachment)
        if (!current(request))
            return UiAttachmentInspectionReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        return if (result is UiAttachmentInspectionReceipt.Ready && result.value.digestVerified) {
            val kind = uiAttachmentMediaKind(result.value.mediaType)
            if (kind == null) result else {
                // The file Owner has just verified exact on-disk SHA-256 and caller identity.
                // A content:// grant never contains a raw workspace path or arbitrary URL.
                val selected = File(files.workspace.path, command.attachment.relativePath)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.files", selected,
                )
                if (!current(request))
                    UiAttachmentInspectionReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
                else UiAttachmentInspectionReceipt.Ready(
                    result.value.copy(media = UiAttachmentMedia(kind, uri.toString())),
                )
            }
        } else result
    }

    override suspend fun import(command: ImportUiAttachmentCommand): UiAttachmentReceipt {
        if (!current(command)) return UiAttachmentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        return try {
            val uri = Uri.parse(command.contentUri)
            // The UI contract only admits a URI returned by an explicit Android document
            // picker. A file:// or other scheme would bypass the user's provider grant.
            if (!isSupportedPickerAttachmentUri(uri.scheme, uri.authority)) {
                return UiAttachmentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
            }
            val imported = files.importAttachment(uri)
            // A copied workspace file does not authorize adding it to a different active composer.
            if (!current(command)) UiAttachmentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
            else UiAttachmentReceipt.Imported(imported)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { attachmentImportFailureReceipt(failure) }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalAttachmentUiPortBindings {
    @dagger.Binds @Singleton
    abstract fun attachments(implementation: LocalAttachmentUiPortComposition): AttachmentUiPort
}
