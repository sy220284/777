package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.UiDocumentMediaKind
import com.labteto.dshmobile.uicontract.UiDocumentNode
import java.io.File

/** Media Owner resolves the requested asset within the document source session. */
@Composable
internal fun NativeV6DocumentMedia(
    node: UiDocumentNode.Media,
    sessionId: String,
    media: MediaUiPort,
) {
    val owned = media.assetForSession(node.assetId, sessionId)
    if (owned == null || (owned.kind != null && owned.kind != node.kind)) {
        Text("媒体结果暂不可用")
        return
    }
    val file = owned.localPath?.let(::File)?.takeIf { it.isFile }
    var playbackError by remember(node.assetId, sessionId) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(node.label)
        owned.textOutput?.let { Text(it) }
        when (node.kind) {
            UiDocumentMediaKind.IMAGE -> MediaInlinePicture(file, owned.remoteUrl)
            UiDocumentMediaKind.AUDIO -> if (file != null) InlineSpeechPlayer(file)
                else Text("音频文件暂不可用")
            UiDocumentMediaKind.VIDEO -> InlineVideoPlayer(owned.remoteUrl, file) {
                playbackError = it
            }
        }
        playbackError?.let { Text(it) }
    }
}
