package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun LocalModelPickerSheet(
    profiles: List<LocalModelProfile>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    DsBottomSheet(
        title = stringResource(R.string.models_title),
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            profiles.forEach { profile ->
                val selected = profile.id == activeProfileId
                val detail = when {
                    selected -> stringResource(R.string.app_model_current)
                    profile.contextWindowTokensOverride != null -> stringResource(
                        R.string.app_model_context, profile.contextWindowTokensOverride,
                    )
                    else -> stringResource(R.string.app_model_available)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(DsShapes.row)
                        .background(if (selected) colors.bgModulePlatform else colors.bgLayer1)
                        .clickable {
                            onSelect(profile.id)
                            onDismiss()
                        }
                        .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_model),
                        contentDescription = null,
                        tint = if (selected) colors.accent else colors.labelSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            profile.displayName ?: profile.model,
                            style = (if (selected) DsType.std14Strong else DsType.std14).withReadingWeight(),
                            color = colors.labelPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            detail,
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ui_check),
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        DsButton(
            text = stringResource(R.string.local_manage_model_config),
            onClick = {
                onDismiss()
                onConfigure()
            },
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Ghost,
        )
    }
}
