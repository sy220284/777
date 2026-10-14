package com.labteto.dshmobile.ui.screens.local


import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyMenuItem

/** Mobile design 11: dismiss the operation surface before invoking the real action. */
@Composable
internal fun FluidMessageActionSheet(expanded: Boolean, onDismiss: () -> Unit, items: List<SyMenuItem>) {
    if (!expanded || items.isEmpty()) return
    SyResponsiveSheet(
        title = stringResource(R.string.fluid_message_actions),
        onDismiss = onDismiss,
        seamlessBackground = true,
    ) {
        items.forEach { item ->
            SyButton(text = item.text, icon = item.icon, modifier = Modifier.fillMaxWidth(),
                variant = if (item.danger) SyButtonVariant.Danger else SyButtonVariant.Ghost,
                onClick = { onDismiss(); item.onClick() })
        }
    }
}

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
