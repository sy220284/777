package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SyMenuItem

@Composable
internal fun fluidMessageMediaItems(message: LocalHarnessMessage,
    onCreate: (LocalHarnessMessage, LocalMediaKind) -> Unit): List<SyMenuItem> =
    listOf(
        Triple(LocalMediaKind.IMAGE, R.string.fluid_message_media_image, FeatherIcons.Image),
        Triple(LocalMediaKind.VIDEO, R.string.fluid_message_media_video, FeatherIcons.Video),
        Triple(LocalMediaKind.SPEECH, R.string.fluid_message_media_speech, FeatherIcons.Activity),
    ).map { (kind, label, glyph) ->
        SyMenuItem(text = stringResource(label), icon = glyph,
            onClick = { onCreate(message, kind) })
    }
