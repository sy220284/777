package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

/** The two views of a session. The low-frequency switch now lives in the capability sheet. */
internal enum class ChatTab { Chat, Trajectory }

/**
 * Frequency-first chat chrome.
 *
 * Only controls used while actively talking stay resident: the current model, live running state
 * and details. Presets, subagents and trajectory remain one tap away in the
 * capability sheet instead of taking a permanent second and third row from every conversation.
 */
@Composable
internal fun ChatTopBar(
    title: String,
    running: Boolean,
    models: SessionModelsValue?,
    modelsLoading: Boolean,
    detailsOpen: Boolean,
    onOpenModels: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Column(modifier.fillMaxWidth().background(colors.wallpaperSurface(WallpaperSurfaceLevel.CHROME, BackgroundRegion.TOP))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = DsType.base16Strong.withReadingWeight(),
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DsSpacing.small))
            DsIconButton(
                icon = FeatherIcons.FileText,
                contentDescription = stringResource(R.string.chat_open_files),
                onClick = onOpenFiles,
                tint = colors.labelTertiary,
                iconSize = 18.dp,
            )
            if (!detailsOpen) {
                Spacer(Modifier.width(DsSpacing.small))
                DsIconButton(
                    icon = FeatherIcons.Info,
                    contentDescription = stringResource(R.string.chat_details_title),
                    onClick = onOpenDetails,
                    tint = colors.labelTertiary,
                    iconSize = 18.dp,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(
                    start = DsSpacing.comfortable,
                    end = DsSpacing.comfortable,
                    bottom = DsSpacing.small,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelChip(
                models = models,
                loading = modelsLoading,
                onClick = onOpenModels,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (running) {
                Spacer(Modifier.width(DsSpacing.small))
                DsStatusPill(
                    state = DsStatus.Running,
                    label = stringResource(R.string.chat_running_status),
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
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val current = models?.current
    val group = models?.let { catalog ->
        current?.let { selected -> catalog.groups.firstOrNull { it.id == selected.provider } }
    }
    val model = current?.let { selected -> group?.models?.firstOrNull { it.id == selected.model } }
    val effort = current?.let { selected ->
        model?.reasoning?.efforts?.firstOrNull { it.id == selected.reasoningEffort }
    }
    val modelLabel = when {
        models != null && current != null -> model?.name ?: current.model
        loading -> stringResource(R.string.common_loading)
        else -> stringResource(R.string.models_title)
    }

    Row(
        modifier = modifier
            .widthIn(max = 220.dp)
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.pillFull)
            .background(colors.hoverSolid)
            .border(1.dp, colors.borderL1, DsShapes.pillFull)
            .clickable(onClick = onClick)
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        if (models != null && !models.routable) StateDot(StateDotState.Warning, size = 6.dp)
        Text(
            modelLabel,
            style = DsType.small13.withReadingWeight(),
            color = if (loading && models == null) colors.labelTertiary else colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        effort?.let {
            Text(it.name, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary, maxLines = 1)
        }
        Icon(
            FeatherIcons.ChevronDown,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(12.dp),
        )
    }
}

