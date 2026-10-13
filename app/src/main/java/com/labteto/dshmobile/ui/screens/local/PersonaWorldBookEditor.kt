package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaLoreEntry
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SySlider
import com.shenyan.designsystem.components.SySwitchControl
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.util.UUID
import kotlin.math.roundToInt

/** Edits the PersonaProfile's existing world-book; does not create a second source of truth. */
@Composable
internal fun PersonaWorldBookEditor(
    entries: List<PersonaLoreEntry>,
    onChange: (List<PersonaLoreEntry>) -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    // A different character's world-book must not inherit the previous editor selection.
    var expandedIndex by rememberSaveable(entries.firstOrNull()?.id) { mutableIntStateOf(0) }
    Text(
        stringResource(R.string.local_persona_lore_title),
        style = DsType.base16Strong.withReadingWeight(),
        color = colors.text,
    )
    Text(
        stringResource(R.string.local_persona_lore_hint),
        style = DsType.small13.withReadingWeight(),
        color = colors.textSecondary,
    )
    // The reference layout has a stable entry list and a separate editing panel.
    // Keep the original PersonaProfile draft callback as the single owner of data.
    // Keep the same state/data source on both form factors. For >=600dp the
    // reference is a two-column master/detail editor; phones stack list and edit.
    val entryList: @Composable () -> Unit = {
        SyMaterialSurface(
            shape = DsShapes.block,
            tier = SyMaterialTier.L3_CARD,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                if (entries.isEmpty()) {
                    Text(
                        stringResource(R.string.local_persona_lore_empty),
                        style = DsType.small13.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                }
                entries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                        ) {
                            Text(
                                entry.title.ifBlank { stringResource(R.string.local_persona_lore_entry_number, index + 1) },
                                style = DsType.small13Strong.withReadingWeight(),
                                color = colors.text,
                            )
                            if (entry.keywords.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.local_persona_lore_entry_keywords) + " · " +
                                        entry.keywords.take(3).joinToString(" · "),
                                    style = DsType.caption11.withReadingWeight(),
                                    color = colors.textTertiary,
                                )
                            }
                        }
                        SyButton(
                            text = stringResource(if (expandedIndex == index) R.string.local_persona_lore_collapse else R.string.local_persona_lore_edit),
                            onClick = { expandedIndex = if (expandedIndex == index) -1 else index },
                            variant = SyButtonVariant.Ghost,
                            modifier = Modifier.testTag("personaLoreSelect:$index"),
                        )
                    }
                }
                SyButton(
                    text = stringResource(R.string.local_persona_lore_add),
                    onClick = {
                        onChange(entries + PersonaLoreEntry(id = "lore-${UUID.randomUUID()}"))
                        expandedIndex = entries.size
                    },
                    enabled = entries.size < 80,
                    variant = SyButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth().testTag("personaLoreAdd"),
                )
            }
        }
    }
    val entryEditor: @Composable () -> Unit = {
        expandedIndex.takeIf { it in entries.indices }?.let { index ->
            val entry = entries[index]
            SyMaterialSurface(
                shape = DsShapes.block,
                tier = SyMaterialTier.L3_CARD,
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
                        color = colors.text,
                    )
                    SyTextField(
                        value = entry.title,
                        onValueChange = { change(entry.copy(title = it)) },
                        label = { Text(stringResource(R.string.local_persona_lore_entry_title)) },
                        modifier = Modifier.fillMaxWidth().testTag("personaLoreTitle"),
                        singleLine = true,
                    )
                    SyTextField(
                        value = entry.content,
                        onValueChange = { change(entry.copy(content = it)) },
                        label = { Text(stringResource(R.string.local_persona_lore_entry_content)) },
                        modifier = Modifier.fillMaxWidth().testTag("personaLoreContent"),
                        minLines = 3,
                        maxLines = 8,
                    )
                    SyTextField(
                        value = entry.keywords.joinToString("\n"),
                        onValueChange = { change(entry.copy(keywords = splitLoreEditorLines(it))) },
                        label = { Text(stringResource(R.string.local_persona_lore_entry_keywords)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                    SyTextField(
                        value = entry.temporalScope,
                        onValueChange = { change(entry.copy(temporalScope = it.take(160))) },
                        label = { Text(stringResource(R.string.persona_v4_story_stage_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    SyTextField(
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
                            color = colors.text,
                        )
                        SySwitchControl(
                            checked = entry.alwaysOn,
                            onCheckedChange = { change(entry.copy(alwaysOn = it)) },
                        )
                    }
                    Text(
                        stringResource(R.string.local_persona_lore_priority, entry.priority),
                        style = DsType.small13.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                    SySlider(
                        value = entry.priority.toFloat().coerceIn(0f, 100f),
                        onValueChange = { change(entry.copy(priority = it.roundToInt())) },
                        valueRange = 0f..100f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SyButton(
                        text = stringResource(R.string.local_persona_lore_spoiler, entry.spoilerLevel),
                        onClick = { change(entry.copy(spoilerLevel = (entry.spoilerLevel + 1) % 4)) },
                        variant = SyButtonVariant.Ghost,
                    )
                    SyButton(
                        text = stringResource(R.string.local_persona_lore_remove),
                        onClick = {
                            onChange(entries.filterIndexed { position, _ -> position != index })
                            expandedIndex = -1
                        },
                        variant = SyButtonVariant.Ghost,
                        modifier = Modifier.testTag("personaLoreRemove"),
                    )
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) { entryList() }
                Column(Modifier.weight(1f)) { entryEditor() }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                entryList()
                entryEditor()
            }
        }
    }

}

private fun splitLoreEditorLines(value: String): List<String> =
    value.split("\n") // Preserve spaces and trailing newlines until the persona is saved.
