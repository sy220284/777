package com.labteto.dshmobile.ui.screens.settings


import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.ui.shenyan.SyMenu
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.theme.SyType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun LocalModelProtocolPicker(
    protocol: LocalModelProtocol,
    available: List<LocalModelProtocol> = LocalModelProtocol.entries.toList(),
    onSelect: (LocalModelProtocol) -> Unit,
) {
    val names = mapOf(
        LocalModelProtocol.CHAT_COMPLETIONS to "Chat Completions",
        LocalModelProtocol.RESPONSES to "Responses",
        LocalModelProtocol.ANTHROPIC_MESSAGES to "Anthropic Messages",
    )
    SyMenu(
        anchor = {
            Text(
                stringResource(R.string.local_model_protocol_value, names.getValue(protocol)),
                style = SyType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            )
        },
        items = names.filterKeys { it in available }
            .map { (value, name) -> SyMenuItem(name) { onSelect(value) } },
    )
}
