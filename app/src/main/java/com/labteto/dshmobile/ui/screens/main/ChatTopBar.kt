package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** The two views of a session. The low-frequency switch now lives in the capability sheet. */
internal enum class ChatTab { Chat, Trajectory }

/**
 * Frequency-first chat chrome.
 *
 * Only controls used while actively talking stay resident: session navigation, the current model,
 * live running state and details. Presets, subagents and trajectory remain one tap away in the
 * capability sheet instead of taking a permanent second and third row from every conversation.
 */
@Composable
internal fun ChatTopBar(
    title: String,
    running: Boolean,
    models: SessionModelsValue?,
    detailsOpen: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Column(modifier.fillMaxWidth().background(colors.bgBase)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = FeatherIcons.Menu,
                contentDescription = stringResource(R.string.chatlist_open),
                onClick = onOpenDrawer,
                tint = colors.labelSecondary,
                iconSize = 18.dp,
                containerColor = colors.bgLayer1,
                shadowElevation = 2.dp,
            )
            Spacer(Modifier.width(DsSpacing.medium))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (title.isNotBlank()) {
                    Text(
                        title,
                        style = DsType.std14Strong,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ModelChip(models = models, onClick = onOpenModels)
            }
            if (running) {
                Spacer(Modifier.width(DsSpacing.small))
                StateDot(StateDotState.Running, size = 8.dp)
            }
            if (!detailsOpen) {
                Spacer(Modifier.width(DsSpacing.small))
                DsIconButton(
                    icon = FeatherIcons.Info,
                    contentDescription = stringResource(R.string.chat_details_title),
                    onClick = onOpenDetails,
                    tint = colors.labelTertiary,
                    iconSize = 18.dp,
                    containerColor = colors.bgLayer1,
                    shadowElevation = 2.dp,
                )
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderL1),
        )
    }
}

/** Compact model switcher: visible because it is frequently changed, quiet because chat is primary. */
@Composable
private fun ModelChip(
    models: SessionModelsValue?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    if (models == null) {
        Box(
            modifier
                .width(96.dp)
                .height(12.dp)
                .skeleton(colors.bgLayer2, colors.hover),
        )
        return
    }
    val current = models.current
    val group = models.groups.firstOrNull { it.id == current.provider }
    val model = group?.models?.firstOrNull { it.id == current.model }
    val effort = model?.reasoning?.efforts?.firstOrNull { it.id == current.reasoningEffort }
    val modelLabel = model?.name ?: current.model

    Row(
        modifier = modifier
            .widthIn(max = 220.dp)
            .heightIn(min = 30.dp)
            .clip(DsShapes.pillFull)
            .background(colors.hoverSolid)
            .border(1.dp, colors.borderL1, DsShapes.pillFull)
            .clickable(onClick = onClick)
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        if (!models.routable) StateDot(StateDotState.Warning, size = 6.dp)
        Text(
            modelLabel,
            style = DsType.small13,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        effort?.let {
            Text(it.name, style = DsType.caption11, color = colors.labelTertiary, maxLines = 1)
        }
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(12.dp),
        )
    }
}
