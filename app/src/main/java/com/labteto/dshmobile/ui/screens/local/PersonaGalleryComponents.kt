package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface


import com.labteto.dshmobile.ui.components.FeatherIcons
import com.shenyan.designsystem.components.SyCheckboxControl
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryStory
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.MAX_PERSONA_TRANSFER_BYTES
import com.labteto.dshmobile.local.presentation.PERSONA_WORD_MIME
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.presentation.galleryMessageArchiveKey
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


internal fun personaExportFileName(
    name: String,
    format: PersonaTransferFormat,
): String {
    val safe = name.trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .take(48)
        .ifBlank { "persona" }
    return "$safe.persona.${format.extension}"
}

internal data class PersonaImportDocument(
    val bytes: ByteArray,
    val fileName: String?,
    val mimeType: String?,
)

internal fun readPersonaShareDocument(
    context: android.content.Context,
    uri: android.net.Uri,
): PersonaImportDocument {
    val resolver = context.contentResolver
    val fileName = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }
    val input = resolver.openInputStream(uri) ?: error(context.getString(R.string.persona_gallery_import_failed))
    val bytes = input.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_PERSONA_TRANSFER_BYTES) {
                "人物导入文件超过 16 MB"
            }
        }
        output.toByteArray()
    }
    return PersonaImportDocument(
        bytes = bytes,
        fileName = fileName,
        mimeType = resolver.getType(uri),
    )
}

@Composable
internal fun DeletePresetConfirmDialog(
    preset: PersonaPreset,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    SyDialog(
        title = stringResource(R.string.persona_gallery_preset_delete_title),
        onDismiss = onCancel,
    ) {
        Text(
            stringResource(R.string.persona_gallery_preset_delete_confirm, preset.persona.name),
            style = DsType.small13.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
        )
        Text(
            stringResource(R.string.persona_gallery_preset_delete_hint),
            style = DsType.caption11.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyButton(
                text = stringResource(R.string.persona_gallery_cancel),
                onClick = onCancel,
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
            SyButton(
                text = stringResource(R.string.persona_gallery_confirm_delete),
                onClick = onConfirm,
                variant = SyButtonVariant.Danger,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun DeleteCharacterConfirm(
    entry: PersonaGalleryEntry,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    SyGlassCard {
        Text(
            stringResource(
                R.string.persona_gallery_delete_saved_confirm,
                entry.persona.name,
                entry.totalDialogueCount(),
            ),
            style = DsType.small13.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk,
        )
        Text(
            stringResource(R.string.persona_gallery_delete_saved_hint),
            style = DsType.caption11.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyButton(
                text = stringResource(R.string.persona_gallery_cancel),
                onClick = onCancel,
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
            SyButton(
                text = stringResource(R.string.persona_gallery_confirm_delete),
                onClick = onConfirm,
                variant = SyButtonVariant.Danger,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }
    }
}

@Composable
internal fun StoryDetailSection(
    entry: PersonaGalleryEntry,
    story: PersonaGalleryStory,
    notes: String,
    onNotesChange: (String) -> Unit,
    showHistory: Boolean,
    onToggleHistory: () -> Unit,
    visibleHistory: Int,
    onMoreHistory: () -> Unit,
    pendingHistoryDeleteKey: String?,
    onPendingHistoryDelete: (String?) -> Unit,
    busy: Boolean,
    onDeleteHistoryMessage: (String) -> Unit,
) {
    if (story.chatState.updatedAt > 0L) {
        SyGlassCard {
            Text(
                stringResource(R.string.persona_gallery_story_relation),
                style = DsType.std14Strong.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
            )
            Text(
                stringResource(R.string.persona_gallery_current_relation, story.chatState.relationshipState),
                style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            )
            story.chatState.currentFocus.takeIf(String::isNotBlank)?.let { focus ->
                Text(focus, style = DsType.small13.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            }
            story.chatState.dynamics.sharedMoments.takeLast(4).takeIf { it.isNotEmpty() }?.let { moments ->
                Text(
                    stringResource(R.string.persona_gallery_shared_moments, moments.joinToString("；")),
                    style = DsType.small13.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                )
            }
            story.chatState.unresolvedThreads.takeLast(3).takeIf { it.isNotEmpty() }?.let { threads ->
                Text(
                    stringResource(R.string.persona_gallery_unresolved, threads.joinToString("；")),
                    style = DsType.small13.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            story.title.ifBlank { stringResource(R.string.persona_gallery_untitled_story) },
            modifier = Modifier.weight(1f),
            style = DsType.std14Strong.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SyButton(
            text = stringResource(
                if (showHistory) R.string.persona_gallery_history_hide
                else R.string.persona_gallery_history_show,
            ),
            onClick = onToggleHistory,
            variant = SyButtonVariant.Ghost,
            size = SyButtonSize.Small,
            enabled = !busy,
        )
    }

    if (showHistory) {
        SyGlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FeatherIcons.Clock,
                    contentDescription = null,
                    tint = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.persona_gallery_archived_dialogue),
                    style = DsType.std14.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                )
            }
            Text(
                stringResource(R.string.persona_gallery_history_long_press_hint),
                style = DsType.caption11.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
            )
            val history = story.history.filter { it.role == "user" || it.role == "assistant" }
            history.takeLast(visibleHistory).forEach { line ->
                val lineKey = galleryMessageArchiveKey(line)
                ArchivedDialogueRow(
                    text = "${if (line.role == "user") stringResource(R.string.persona_gallery_user) else entry.persona.name}：${line.content}",
                    onLongClick = { onPendingHistoryDelete(lineKey) },
                )
            }
            val pendingHistory = pendingHistoryDeleteKey?.let { key ->
                history.firstOrNull { galleryMessageArchiveKey(it) == key }
            }
            pendingHistory?.let { line ->
                SyGlassCard {
                    Text(
                        stringResource(R.string.persona_gallery_delete_dialogue_confirm),
                        style = DsType.small13.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk,
                    )
                    Text(
                        "${if (line.role == "user") stringResource(R.string.persona_gallery_user) else entry.persona.name}：${line.content}",
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.persona_gallery_delete_dialogue_hint),
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyButton(
                            text = stringResource(R.string.persona_gallery_cancel),
                            onClick = { onPendingHistoryDelete(null) },
                            variant = SyButtonVariant.Ghost,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                        SyButton(
                            text = stringResource(R.string.persona_gallery_confirm_delete),
                            onClick = { onDeleteHistoryMessage(galleryMessageArchiveKey(line)) },
                            variant = SyButtonVariant.Danger,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                    }
                }
            }
            if (history.size > visibleHistory) {
                SyButton(
                    text = stringResource(R.string.persona_gallery_older_dialogue),
                    onClick = onMoreHistory,
                    modifier = Modifier.fillMaxWidth(),
                    variant = SyButtonVariant.Ghost,
                )
            }
        }
    }

    var editingNotes by rememberSaveable(entry.id, story.id) { mutableStateOf(false) }
    SyGlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.persona_gallery_story_notes),
                modifier = Modifier.weight(1f),
                style = DsType.std14Strong.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
            )
            SyButton(
                text = stringResource(
                    if (editingNotes) R.string.persona_gallery_notes_done
                    else if (notes.isBlank()) R.string.persona_gallery_notes_add
                    else R.string.persona_gallery_notes_edit,
                ),
                onClick = { editingNotes = !editingNotes },
                size = SyButtonSize.Small,
                variant = SyButtonVariant.Ghost,
                enabled = !busy,
            )
        }
        if (editingNotes) {
            SyTextField(
                value = notes,
                onValueChange = onNotesChange,
                placeholder = { Text(stringResource(R.string.persona_gallery_story_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                enabled = !busy,
            )
        } else {
            Text(
                notes.ifBlank { stringResource(R.string.persona_gallery_notes_empty) },
                style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            )
        }
    }

}

@Composable
internal fun GalleryStoryCard(
    story: PersonaGalleryStory,
    selected: Boolean,
    unsaved: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .background(if (selected) com.shenyan.designsystem.foundation.LocalSyColors.current.selected
                else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    story.title.ifBlank { stringResource(R.string.persona_gallery_untitled_story) },
                    style = (if (selected) DsType.std14Strong else DsType.std14).withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = listOf(story.chatState.relationshipState, story.chatState.mood)
                    .filter(String::isNotBlank).joinToString(" · ")
                if (summary.isNotBlank() || selected) {
                    Text(
                        if (selected) listOf(stringResource(R.string.persona_gallery_current_story_badge), summary)
                            .filter(String::isNotBlank).joinToString(" · ") else summary,
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (unsaved) {
                GalleryPill(stringResource(R.string.persona_gallery_unsaved_badge))
                Spacer(Modifier.width(6.dp))
            }
            GalleryPill(
                stringResource(
                    R.string.persona_gallery_dialogue_count,
                    story.history.count { it.role == "user" || it.role == "assistant" },
                ),
            )
        }
    }
}

@Composable
private fun ArchivedDialogueRow(
    text: String,
    onLongClick: () -> Unit,
) {
    SyMaterialSurface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onLongClick,
                onLongClick = onLongClick,
            ),
        shape = DsShapes.row,
        tier = SyMaterialTier.L3_CARD,
    ) {
        Text(
            text,
            style = DsType.small13.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            modifier = Modifier.padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapMd),
        )
    }
}

@Composable
internal fun PersonaHero(persona: PersonaProfile, subtitle: String) {
    SyGlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonaAvatar(persona.name, large = true)
            Spacer(Modifier.width(SyMetric.gapLg))
            Column(Modifier.weight(1f)) {
                Text(
                    persona.name,
                    style = DsType.large20.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                )
                Text(
                    persona.coreIdentity.ifBlank { subtitle },
                    style = DsType.small13.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (persona.coreIdentity.isNotBlank()) {
                    Text(subtitle, style = DsType.caption11.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun PersonaAvatar(name: String, large: Boolean = false) {
    val size = if (large) 56.dp else 44.dp
    val fallback = stringResource(R.string.persona_gallery_avatar_fallback)
    val initial = name.trim().firstOrNull()?.toString().orEmpty().ifBlank { fallback }
    Surface(
        shape = CircleShape,
        color = com.shenyan.designsystem.foundation.LocalSyColors.current.primary.copy(alpha = 0.13f),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                initial,
                style = (if (large) DsType.large20 else DsType.std14Strong).withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.primary,
            )
        }
    }
}

@Composable
internal fun GalleryPill(text: String) {
    SyMaterialSurface(
        shape = RoundedCornerShape(999.dp),
        tier = SyMaterialTier.L3_CARD,
    ) {
        Text(
            text,
            style = DsType.caption11.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun PersonaDetails(persona: PersonaProfile) {
    val scalars = listOf(
        stringResource(R.string.persona_v4_core_identity) to persona.coreIdentity,
        stringResource(R.string.persona_field_franchise) to persona.franchise,
    ).filter { it.second.isNotBlank() }
    val facts = persona.facts.filter { it.content.isNotBlank() }
    if (scalars.isEmpty() && facts.isEmpty() && persona.loreEntries.isEmpty()) return
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current

    // A readable character profile, not a transcript packed inside a second glass card.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Text(
            stringResource(R.string.persona_gallery_fixed_persona),
            style = DsType.base16Strong.withReadingWeight(),
            color = colors.text,
        )
        scalars.forEach { (label, value) ->
            PersonaDetailRow(label, value)
        }
        facts.forEach { fact ->
            val labelRes = when (fact.category) {
                "personality" -> R.string.persona_v4_personality
                "selfNarrative" -> R.string.persona_v4_self_narrative
                "identityGap" -> R.string.persona_v4_identity_gap
                "valuesAndTradeoffs" -> R.string.persona_v4_values_tradeoffs
                "subjectiveBeliefs" -> R.string.persona_v4_subjective_beliefs
                "biography" -> R.string.persona_v4_biography
                "definingChoices" -> R.string.persona_v4_defining_choices
                "emotionalImprints" -> R.string.persona_v4_emotional_imprints
                "lifeGravity" -> R.string.persona_v4_life_gravity
                "unfinishedBusiness" -> R.string.persona_v4_unfinished_business
                "relationships" -> R.string.persona_v4_relationships
                "limitsAndCosts" -> R.string.persona_v4_limits_costs
                "sensorySignature" -> R.string.persona_v4_sensory_signature
                "preferencesAndHabits" -> R.string.persona_v4_preferences_habits
                "voiceStyle" -> R.string.persona_v4_voice_style
                "customFacts" -> R.string.persona_v4_custom_facts
                else -> null
            }
            val provenanceInferred = stringResource(R.string.persona_v4_provenance_inferred)
            val provenanceUnverified = stringResource(R.string.persona_v4_provenance_unverified)
            val stageLabel = stringResource(R.string.persona_v4_story_stage_label)
            val sourceLabel = stringResource(R.string.persona_v4_fact_source)
            val extra = buildList {
                when (fact.provenance) {
                    com.labteto.dshmobile.local.chat.CharacterFactProvenance.INFERRED -> add(provenanceInferred)
                    com.labteto.dshmobile.local.chat.CharacterFactProvenance.UNVERIFIED -> add(provenanceUnverified)
                    else -> Unit
                }
                fact.temporalScope.takeIf(String::isNotBlank)?.let { add(stageLabel + "：" + it) }
                fact.sourceReference.takeIf(String::isNotBlank)?.let { add(sourceLabel + "：" + it) }
            }
            PersonaDetailRow(
                title = labelRes?.let { stringResource(it) } ?: fact.category,
                value = fact.content,
                annotation = extra.joinToString(" · "),
            )
        }
        if (persona.loreEntries.isNotEmpty()) {
            PersonaDetailRow(
                stringResource(R.string.persona_field_lore),
                persona.loreEntries.joinToString("；") { it.title.ifBlank { it.content.take(80) } },
            )
        }
    }
}

@Composable
private fun PersonaDetailRow(title: String, value: String, annotation: String = "") {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapSm),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
    ) {
        Text(title, style = DsType.std14Strong.withReadingWeight(), color = colors.text)
        Text(
            value,
            style = DsType.base16.copy(fontFamily = DsType.contentFont).withReadingWeight(),
            color = colors.textSecondary,
        )
        if (annotation.isNotBlank()) {
            Text(annotation, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
        }
    }
}

@Composable
internal fun PersonaInspectionPanel(
    result: PersonaInspectionResult,
    selectedKeys: List<String>,
    onToggle: (PersonaAppendSuggestion) -> Unit,
) {
    if (result.conflicts.isEmpty() && result.suggestions.isEmpty()) {
        SyGlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FeatherIcons.User,
                    contentDescription = null,
                    tint = com.shenyan.designsystem.foundation.LocalSyColors.current.lilac,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.persona_gallery_inspection_clean),
                    style = DsType.small13.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                )
            }
        }
        return
    }

    if (result.conflicts.isNotEmpty()) {
        Text(
            stringResource(R.string.persona_gallery_conflict_count, result.conflicts.size),
            style = DsType.std14Strong.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
        )
        result.conflicts.forEach { conflict ->
            SyGlassCard {
                Text(
                    personaFieldLabel(conflict.field),
                    style = DsType.caption11.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk,
                )
                if (conflict.fixedValue.isNotBlank()) {
                    Text(
                        stringResource(R.string.persona_gallery_fixed_value, conflict.fixedValue),
                        style = DsType.small13.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                    )
                }
                Text(
                    stringResource(R.string.persona_gallery_observed_value, conflict.observedValue),
                    style = DsType.small13.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                )
                if (conflict.reason.isNotBlank()) {
                    Text(
                        conflict.reason,
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                    )
                }
            }
        }
    }

    if (result.suggestions.isNotEmpty()) {
        Text(
            stringResource(R.string.persona_gallery_suggestions_title),
            style = DsType.std14Strong.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
        )
        result.suggestions.forEach { suggestion ->
            val key = suggestionKey(suggestion)
            SyGlassCard(onClick = { onToggle(suggestion) }) {
                Row(verticalAlignment = Alignment.Top) {
                    SyCheckboxControl(
                        checked = key in selectedKeys,
                        onCheckedChange = { onToggle(suggestion) },
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            personaFieldLabel(suggestion.field),
                            style = DsType.caption11.withReadingWeight(),
                            color = com.shenyan.designsystem.foundation.LocalSyColors.current.primary,
                        )
                        Text(
                            suggestion.value,
                            style = DsType.small13.withReadingWeight(),
                            color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                        )
                        if (suggestion.evidence.isNotBlank()) {
                            Text(
                                stringResource(R.string.persona_gallery_evidence, suggestion.evidence),
                                style = DsType.caption11.withReadingWeight(),
                                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun suggestionKey(suggestion: PersonaAppendSuggestion): String =
    "${suggestion.field}|${suggestion.value.trim()}"

@Composable
private fun personaFieldLabel(field: String): String = when (field) {
    "identity" -> stringResource(R.string.persona_field_identity)
    "background" -> stringResource(R.string.persona_field_background)
    "personality" -> stringResource(R.string.persona_field_personality)
    "speechStyle" -> stringResource(R.string.persona_field_speech_style)
    "relationship" -> stringResource(R.string.persona_field_relationship)
    "worldSetting" -> stringResource(R.string.persona_field_world_setting)
    "franchise" -> stringResource(R.string.persona_field_franchise)
    "timelinePosition" -> stringResource(R.string.persona_field_timeline)
    "coreMotivations" -> stringResource(R.string.persona_field_motivations)
    "valuePriorities" -> stringResource(R.string.persona_field_values)
    "behaviorPatterns" -> stringResource(R.string.persona_field_behavior_patterns)
    "internalContradictions" -> stringResource(R.string.persona_field_internal_contradictions)
    "knowledgeBoundary" -> stringResource(R.string.persona_field_knowledge_boundary)
    "hardConstraints" -> stringResource(R.string.persona_field_constraints)
    "exampleDialogues" -> stringResource(R.string.persona_field_dialogues)
    "bannedPhrases" -> stringResource(R.string.persona_field_banned)
    "signaturePhrases" -> stringResource(R.string.persona_field_signature)
    "corrections" -> stringResource(R.string.persona_field_corrections)
    else -> field
}
