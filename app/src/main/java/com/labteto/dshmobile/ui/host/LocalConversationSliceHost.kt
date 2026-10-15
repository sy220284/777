package com.labteto.dshmobile.ui.host

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.screens.local.LocalHarnessViewModel
import com.labteto.dshmobile.uihost.UiPackAdmission
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uipack.next.NextConversationUiPack

/** Debug-only explicit slice entry. Full application substitution remains unimplemented. */
@Composable
internal fun LocalConversationSliceHost(
    requestedSessionId: String?,
    onSessionRequestConsumed: () -> Unit,
    onClose: () -> Unit,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val shell by viewModel.shellState.collectAsStateWithLifecycle()
    val widthDp = LocalConfiguration.current.screenWidthDp
    val windowClass = when {
        widthDp >= 840 -> UiWindowClass.EXPANDED
        widthDp >= 600 -> UiWindowClass.MEDIUM
        else -> UiWindowClass.COMPACT
    }
    val admission = LocalConversationPackGate.admit(
        packId = NextConversationUiPack.manifest.id,
        availableCapabilities = LocalConversationPackGate.capabilities(
            chat = viewModel.chatUiPort,
            work = viewModel.workConversationUiPort,
            drafts = viewModel.conversationDraftUiPort,
            documents = viewModel.nextUiDocumentUiPort,
            forms = viewModel.uiDocumentFormPort,
            attachments = viewModel.attachmentUiPort,
            transcript = viewModel.messageHistoryUiPort,
            groups = viewModel.chatGroupUiPort,
            team = viewModel.workTeamUiPort,
            shell = viewModel.conversationShellUiPort,
            evidence = viewModel.workEvidenceUiPort,
            gallery = viewModel.personaGalleryUiPort,
            personas = viewModel.personaSelectionUiPort,
            personaTransfer = viewModel.personaTransferUiPort,
            stories = viewModel.personaStoryUiPort,
            galleryDeletion = viewModel.personaGalleryDeletionUiPort,
            gallerySave = viewModel.personaGallerySaveUiPort,
            portraits = viewModel.personaPortraitUiPort,
        ),
        windowClass = windowClass,
        dark = isSystemInDarkTheme(),
    )
    LaunchedEffect(requestedSessionId, shell.loading, admission) {
        if (admission is UiPackAdmission.Admitted && !shell.loading && requestedSessionId != null) {
            if (viewModel.switchSession(requestedSessionId)) onSessionRequestConsumed()
        }
    }
    BackHandler(onBack = onClose)
    when (admission) {
        is UiPackAdmission.Admitted -> admission.pack.Render(
            documents = viewModel.nextUiDocumentUiPort,
            forms = viewModel.uiDocumentFormPort,
            reading = viewModel.conversationReadingUiPort,
            drafts = viewModel.conversationDraftUiPort,
            chat = viewModel.chatUiPort,
            work = viewModel.workConversationUiPort,
            workMode = shell.usageMode == LocalUsageMode.WORK,
            onClose = onClose,
            attachments = viewModel.attachmentUiPort,
            sessions = viewModel.sessionUiPort,
            transcript = viewModel.messageHistoryUiPort,
            policy = viewModel.workPolicyUiPort,
            workReads = viewModel.workReadUiPort,
            files = viewModel.filesUiPort,
            groups = viewModel.chatGroupUiPort,
            team = viewModel.workTeamUiPort,
            shell = viewModel.conversationShellUiPort,
            evidence = viewModel.workEvidenceUiPort,
            gallery = viewModel.personaGalleryUiPort,
            personas = viewModel.personaSelectionUiPort,
            personaTransfer = viewModel.personaTransferUiPort,
            stories = viewModel.personaStoryUiPort,
            galleryDeletion = viewModel.personaGalleryDeletionUiPort,
            gallerySave = viewModel.personaGallerySaveUiPort,
            portraits = viewModel.personaPortraitUiPort,
        )
        is UiPackAdmission.Rejected, is UiPackAdmission.UnknownPack -> MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.ui_pack_incompatible), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.ui_pack_back_to_stable))
                    }
                }
            }
        }
    }
}
