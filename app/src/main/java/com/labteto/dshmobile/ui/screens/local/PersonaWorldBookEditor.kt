package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaLoreEntry
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsSlider
import com.labteto.dshmobile.ui.components.DsSwitch
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.util.UUID
import kotlin.math.roundToInt

/** Edits the PersonaProfile's existing world-book; does not create a second source of truth. */
@Composable
internal fun PersonaWorldBookEditor(
    entries: List<PersonaLoreEntry>,
    onChange: (List<PersonaLoreEntry>) -> Unit,
) {
    val colors = DsTheme.colors
    var expandedIndex by rememberSaveable { mutableIntStateOf(-1) }
    Text(
        stringResource(R.string.local_persona_lore_title),
        style = DsType.base16Strong.withReadingWeight(),
        color = colors.labelPrimary,
    )
    Text(
        stringResource(R.string.local_persona_lore_hint),
        style = DsType.small13.withReadingWeight(),
        color = colors.labelSecondary,
    )
    if (entries.isEmpty()) {
        Text(
            stringResource(R.string.local_persona_lore_empty),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
        )
    }
    entries.forEachIndexed { index, entry ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                entry.title.ifBlank { stringResource(R.string.local_persona_lore_entry_number, index + 1) },
                modifier = Modifier.weight(1f),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelPrimary,
            )
            DsButton(
                text = stringResource(if (expandedIndex == index) R.string.local_persona_lore_collapse else R.string.local_persona_lore_edit),
                onClick = { expandedIndex = if (expandedIndex == index) -1 else index },
                variant = DsButtonVariant.Ghost,
            )
        }
        if (expandedIndex == index) Surface(
            shape = DsShapes.block,
            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                fun change(next: PersonaLoreEntry) {
                    onChange(entries.toMutableList().also { it[index] = next })
                }
                Text(
                    stringResource(R.string.local_persona_lore_entry_number, index + 1),
                    style = DsType.base16Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                DsTextField(
                    value = entry.title,
                    onValueChange = { change(entry.copy(title = it)) },
                    label = { Text(stringResource(R.string.local_persona_lore_entry_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                DsTextField(
                    value = entry.content,
                    onValueChange = { change(entry.copy(content = it)) },
                    label = { Text(stringResource(R.string.local_persona_lore_entry_content)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
                DsTextField(
                    value = entry.keywords.joinToString("\n"),
                    onValueChange = { change(entry.copy(keywords = splitLoreEditorLines(it))) },
                    label = { Text(stringResource(R.string.local_persona_lore_entry_keywords)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                DsTextField(
                    value = entry.secondaryKeywords.joinToString("\n"),
                    onValueChange = { change(entry.copy(secondaryKeywords = splitLoreEditorLines(it))) },
                    label = { Text(stringResource(R.string.local_persona_lore_entry_secondary_keywords)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.local_persona_lore_always_on),
                        modifier = Modifier.weight(1f),
                        style = DsType.small13.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                    DsSwitch(
                        checked = entry.alwaysOn,
                        onCheckedChange = { change(entry.copy(alwaysOn = it)) },
                    )
                }
                Text(
                    stringResource(R.string.local_persona_lore_priority, entry.priority),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelSecondary,
                )
                DsSlider(
                    value = entry.priority.toFloat().coerceIn(0f, 100f),
                    onValueChange = { change(entry.copy(priority = it.roundToInt())) },
                    valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth(),
                )
                DsButton(
                    text = stringResource(R.string.local_persona_lore_spoiler, entry.spoilerLevel),
                    onClick = { change(entry.copy(spoilerLevel = (entry.spoilerLevel + 1) % 4)) },
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.local_persona_lore_remove),
                    onClick = {
                        onChange(entries.filterIndexed { position, _ -> position != index })
                        expandedIndex = -1
                    },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
    DsButton(
        text = stringResource(R.string.local_persona_lore_add),
        onClick = {
            onChange(entries + PersonaLoreEntry(id = "lore-${UUID.randomUUID()}"))
            expandedIndex = entries.size
        },
        enabled = entries.size < 80,
        variant = DsButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun splitLoreEditorLines(value: String): List<String> =
    value.split("\n") // Preserve spaces and trailing newlines until the persona is saved.
