package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryStory
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaPreset
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.MAX_PERSONA_TRANSFER_BYTES
import com.labteto.dshmobile.local.chat.PERSONA_WORD_MIME
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.chat.galleryMessageArchiveKey
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PersonaGalleryTopBar(
    title: String,
    onBack: () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CHROME, BackgroundRegion.TOP, colors.bgBase),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack,
            )
            Text(
                text = title,
                style = DsType.large20.withReadingWeight(),
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

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
    DsDialog(
        title = stringResource(R.string.persona_gallery_preset_delete_title),
        onDismiss = onCancel,
    ) {
        Text(
            stringResource(R.string.persona_gallery_preset_delete_confirm, preset.persona.name),
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelPrimary,
        )
        Text(
            stringResource(R.string.persona_gallery_preset_delete_hint),
            style = DsType.caption11.withReadingWeight(),
            color = DsTheme.colors.labelTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DsButton(
                text = stringResource(R.string.persona_gallery_cancel),
                onClick = onCancel,
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_confirm_delete),
                onClick = onConfirm,
                variant = DsButtonVariant.Danger,
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
    DsCard {
        Text(
            stringResource(
                R.string.persona_gallery_delete_saved_confirm,
                entry.persona.name,
                entry.totalDialogueCount(),
            ),
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.error,
        )
        Text(
            stringResource(R.string.persona_gallery_delete_saved_hint),
            style = DsType.caption11.withReadingWeight(),
            color = DsTheme.colors.labelTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DsButton(
                text = stringResource(R.string.persona_gallery_cancel),
                onClick = onCancel,
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_confirm_delete),
                onClick = onConfirm,
                variant = DsButtonVariant.Danger,
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
        DsCard {
            Text(
                stringResource(R.string.persona_gallery_story_relation),
                style = DsType.std14Strong.withReadingWeight(),
                color = DsTheme.colors.labelPrimary,
            )
            Text(
                stringResource(R.string.persona_gallery_current_relation, story.chatState.relationshipState),
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.labelSecondary,
            )
            story.chatState.dynamics.sharedMoments.takeLast(4).takeIf { it.isNotEmpty() }?.let { moments ->
                Text(
                    stringResource(R.string.persona_gallery_shared_moments, moments.joinToString("；")),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary,
                )
            }
            story.chatState.unresolvedThreads.takeLast(3).takeIf { it.isNotEmpty() }?.let { threads ->
                Text(
                    stringResource(R.string.persona_gallery_unresolved, threads.joinToString("；")),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
    }

    DsButton(
        text = if (showHistory) {
            stringResource(R.string.persona_gallery_history_hide)
        } else {
            stringResource(R.string.persona_gallery_history_show)
        },
        onClick = onToggleHistory,
        variant = DsButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth(),
    )
    if (showHistory) {
        DsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = DsTheme.colors.labelSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.persona_gallery_archived_dialogue),
                    style = DsType.std14.withReadingWeight(),
                    color = DsTheme.colors.labelPrimary,
                )
            }
            Text(
                stringResource(R.string.persona_gallery_history_long_press_hint),
                style = DsType.caption11.withReadingWeight(),
                color = DsTheme.colors.labelTertiary,
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
                DsCard {
                    Text(
                        stringResource(R.string.persona_gallery_delete_dialogue_confirm),
                        style = DsType.small13.withReadingWeight(),
                        color = DsTheme.colors.error,
                    )
                    Text(
                        "${if (line.role == "user") stringResource(R.string.persona_gallery_user) else entry.persona.name}：${line.content}",
                        style = DsType.caption11.withReadingWeight(),
                        color = DsTheme.colors.labelSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.persona_gallery_delete_dialogue_hint),
                        style = DsType.caption11.withReadingWeight(),
                        color = DsTheme.colors.labelTertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DsButton(
                            text = stringResource(R.string.persona_gallery_cancel),
                            onClick = { onPendingHistoryDelete(null) },
                            variant = DsButtonVariant.Ghost,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                        DsButton(
                            text = stringResource(R.string.persona_gallery_confirm_delete),
                            onClick = { onDeleteHistoryMessage(galleryMessageArchiveKey(line)) },
                            variant = DsButtonVariant.Danger,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        )
                    }
                }
            }
            if (history.size > visibleHistory) {
                DsButton(
                    text = stringResource(R.string.persona_gallery_older_dialogue),
                    onClick = onMoreHistory,
                    modifier = Modifier.fillMaxWidth(),
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }

    OutlinedTextField(
        value = notes,
        onValueChange = onNotesChange,
        label = { Text(stringResource(R.string.persona_gallery_story_notes)) },
        placeholder = { Text(stringResource(R.string.persona_gallery_story_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 8,
        enabled = !busy,
    )
}

@Composable
internal fun GalleryOverviewHeader(
    characterCount: Int,
    storyCount: Int,
    dialogueCount: Int,
) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = DsTheme.colors.characterAccentTertiary,
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = DsTheme.colors.characterAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(DsSpacing.medium))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                Text(
                    stringResource(R.string.persona_gallery_master),
                    style = DsType.large20.withReadingWeight(),
                    color = DsTheme.colors.labelPrimary,
                )
                Text(
                    stringResource(
                        R.string.persona_gallery_overview_stats,
                        characterCount,
                        storyCount,
                        dialogueCount,
                    ),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
    }
}

@Composable
private fun PersonaPresetArtworkPreview(
    preset: PersonaPreset,
) {
    val context = LocalContext.current
    val assetPath = preset.artwork?.assetPath.orEmpty()
    val artwork by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = assetPath,
    ) {
        value = if (assetPath.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.assets.open(assetPath).use { input ->
                        BitmapFactory.decodeStream(input, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    var sample = 1
                    val longest = maxOf(bounds.outWidth, bounds.outHeight)
                    while (longest / sample > 720) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    context.assets.open(assetPath).use { input ->
                        BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
    }

    val resolvedArtwork = artwork
    if (resolvedArtwork == null) {
        PersonaAvatar(preset.persona.name)
    } else {
        Image(
            bitmap = resolvedArtwork,
            contentDescription = preset.persona.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp)),
        )
    }
}

@Composable
internal fun PersonaPresetCard(
    preset: PersonaPreset,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD, base = DsTheme.colors.bgLayer1),
        shadowElevation = 2.dp,
        modifier = modifier
            .heightIn(min = 196.dp)
            .combinedClickable(
                enabled = !busy,
                onClick = onInstall,
                onLongClick = onLongClick,
            ),
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            PersonaPresetArtworkPreview(preset)
            Text(
                preset.persona.name,
                style = DsType.std14Strong.withReadingWeight(),
                color = DsTheme.colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GalleryPill(preset.franchise)
            Text(
                preset.summary,
                style = DsType.caption11.withReadingWeight(),
                color = DsTheme.colors.labelSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            DsButton(
                text = stringResource(R.string.persona_gallery_preset_add),
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                variant = DsButtonVariant.Outline,
            )
        }
    }
}

@Composable
internal fun GalleryPersonaCard(
    entry: PersonaGalleryEntry,
    unsaved: Boolean,
    modifier: Modifier = Modifier,
    onChoosePortrait: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val waitingText = stringResource(R.string.persona_gallery_waiting)
    val subtitle = when {
        entry.persona.identity.isNotBlank() -> entry.persona.identity
        entry.persona.personality.isNotBlank() -> entry.persona.personality
        else -> waitingText
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD, base = DsTheme.colors.bgLayer1),
        shadowElevation = 4.dp,
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Box(Modifier.fillMaxWidth()) {
                SpatialPortraitStandee(
                    entry = entry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp),
                )
                DsIconButton(
                    icon = Icons.Outlined.Image,
                    contentDescription = stringResource(
                        if (entry.portraitPath.isBlank()) R.string.persona_gallery_portrait_add
                        else R.string.persona_gallery_portrait_replace,
                    ),
                    onClick = onChoosePortrait,
                    modifier = Modifier.align(Alignment.TopEnd),
                    containerColor = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING, base = DsTheme.colors.bgLayer2),
                )
            }
            Text(
                entry.persona.name,
                style = DsType.std14Strong.withReadingWeight(),
                color = DsTheme.colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = DsType.caption11.withReadingWeight(),
                color = DsTheme.colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.persona_gallery_standee_meta,
                    entry.stories.size,
                    entry.totalDialogueCount(),
                ),
                style = DsType.caption11.withReadingWeight(),
                color = DsTheme.colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unsaved) {
                GalleryPill(stringResource(R.string.persona_gallery_unsaved_badge))
            }
        }
    }
}

@Composable
internal fun SpatialPortraitStandee(
    entry: PersonaGalleryEntry,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    var rotationX by remember(entry.id) { mutableStateOf(0f) }
    var rotationY by remember(entry.id) { mutableStateOf(0f) }
    val portrait by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = entry.portraitPath,
    ) {
        value = withContext(Dispatchers.IO) {
            decodeGalleryPortrait(entry.portraitPath)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(entry.id, entry.portraitPath) {
                    detectDragGestures(
                        onDragEnd = {
                            rotationX = 0f
                            rotationY = 0f
                        },
                        onDragCancel = {
                            rotationX = 0f
                            rotationY = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        rotationY = (rotationY + dragAmount.x / 28f).coerceIn(-11f, 11f)
                        rotationX = (rotationX - dragAmount.y / 36f).coerceIn(-7f, 7f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colors.accent.copy(alpha = 0.07f),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .shadow(12.dp, RoundedCornerShape(28.dp))
                    .graphicsLayer {
                        this.rotationX = rotationX
                        this.rotationY = rotationY
                        cameraDistance = 24f * density
                    },
            ) {
                if (portrait != null) {
                    Image(
                        bitmap = portrait!!,
                        contentDescription = entry.persona.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .clip(RoundedCornerShape(22.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.accent.copy(alpha = 0.04f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PersonaAvatar(entry.persona.name, large = true)
                            Spacer(Modifier.size(DsSpacing.small))
                            Text(
                                stringResource(R.string.persona_gallery_portrait_empty),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelTertiary,
                            )
                        }
                    }
                }
            }
        }
        Surface(
            shape = CircleShape,
            color = colors.labelTertiary.copy(alpha = 0.16f),
            modifier = Modifier
                .fillMaxWidth(0.56f)
                .height(10.dp)
                .graphicsLayer {
                    rotationX = 65f
                    cameraDistance = 18f * density
                },
        ) {}
    }
}

private fun decodeGalleryPortrait(path: String): ImageBitmap? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > 1_600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}

@Composable
internal fun GalleryStoryCard(
    story: PersonaGalleryStory,
    selected: Boolean,
    unsaved: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = DsTheme.colors.wallpaperSurface(
            WallpaperSurfaceLevel.CARD,
            base = if (selected) DsTheme.colors.accentTertiary else DsTheme.colors.bgLayer1,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    story.title.ifBlank { stringResource(R.string.persona_gallery_untitled_story) },
                    style = (if (selected) DsType.std14Strong else DsType.std14).withReadingWeight(),
                    color = DsTheme.colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = story.notes.ifBlank { story.chatState.relationshipState }
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = DsType.caption11.withReadingWeight(),
                        color = DsTheme.colors.labelSecondary,
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onLongClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(10.dp),
        color = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD, base = DsTheme.colors.bgLayer2),
    ) {
        Text(
            text,
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelSecondary,
            modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        )
    }
}

@Composable
internal fun PersonaRelationshipStatusCard(story: PersonaGalleryStory) {
    val colors = DsTheme.colors
    val state = story.chatState
    DsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    story.title.ifBlank { stringResource(R.string.persona_gallery_untitled_story) },
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.persona_gallery_story_relation),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
            GalleryPill(state.relationshipState)
            state.mood.takeIf(String::isNotBlank)?.let { GalleryPill(it) }
        }
        state.currentFocus.takeIf(String::isNotBlank)?.let { focus ->
            Text(
                focus,
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.dynamics.sharedMoments.lastOrNull()?.takeIf(String::isNotBlank)?.let { moment ->
            Text(
                moment,
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PersonaHero(persona: PersonaProfile, subtitle: String) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonaAvatar(persona.name, large = true)
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    persona.name,
                    style = DsType.large20.withReadingWeight(),
                    color = DsTheme.colors.labelPrimary,
                )
                Text(
                    persona.identity.ifBlank { subtitle },
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (persona.identity.isNotBlank()) {
                    Text(subtitle, style = DsType.caption11.withReadingWeight(), color = DsTheme.colors.labelTertiary)
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
        color = DsTheme.colors.accent.copy(alpha = 0.13f),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                initial,
                style = (if (large) DsType.large20 else DsType.std14Strong).withReadingWeight(),
                color = DsTheme.colors.accent,
            )
        }
    }
}

@Composable
internal fun GalleryPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD, base = DsTheme.colors.bgLayer1),
    ) {
        Text(
            text,
            style = DsType.caption11.withReadingWeight(),
            color = DsTheme.colors.labelSecondary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun PersonaDetails(persona: PersonaProfile) {
    val scalarSections = listOf(
        stringResource(R.string.persona_field_identity) to persona.identity,
        stringResource(R.string.persona_field_background) to persona.background,
        stringResource(R.string.persona_field_personality) to persona.personality,
        stringResource(R.string.persona_field_speech_style) to persona.speechStyle,
        stringResource(R.string.persona_field_relationship) to persona.relationship,
        stringResource(R.string.persona_field_world_setting) to persona.worldSetting,
        stringResource(R.string.persona_field_franchise) to persona.franchise,
        stringResource(R.string.persona_field_timeline) to persona.timelinePosition,
    ).filter { it.second.isNotBlank() }
    val listSections = listOf(
        stringResource(R.string.persona_field_motivations) to persona.coreMotivations,
        stringResource(R.string.persona_field_values) to persona.valuePriorities,
        stringResource(R.string.persona_field_behavior_patterns) to persona.behaviorPatterns,
        stringResource(R.string.persona_field_internal_contradictions) to persona.internalContradictions,
        stringResource(R.string.persona_field_knowledge_boundary) to persona.knowledgeBoundary,
        stringResource(R.string.persona_field_lore) to persona.loreEntries.map { entry ->
            entry.title.ifBlank { entry.content.take(80) }
        },
        stringResource(R.string.persona_field_constraints) to persona.hardConstraints,
        stringResource(R.string.persona_field_dialogues) to persona.exampleDialogues,
        stringResource(R.string.persona_field_banned) to persona.bannedPhrases,
        stringResource(R.string.persona_field_signature) to persona.signaturePhrases,
        stringResource(R.string.persona_field_corrections) to persona.corrections,
    ).filter { it.second.isNotEmpty() }

    if (scalarSections.isEmpty() && listSections.isEmpty()) return
    DsCard {
        Text(
            stringResource(R.string.persona_gallery_fixed_persona),
            style = DsType.std14Strong.withReadingWeight(),
            color = DsTheme.colors.labelPrimary,
        )
        scalarSections.forEach { (title, value) ->
            PersonaDetailRow(title, value)
        }
        listSections.forEach { (title, values) ->
            PersonaDetailRow(title, values.joinToString("；"))
        }
    }
}

@Composable
private fun PersonaDetailRow(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = DsType.caption11.withReadingWeight(), color = DsTheme.colors.labelTertiary)
        Text(value, style = DsType.small13.withReadingWeight(), color = DsTheme.colors.labelSecondary)
    }
}

@Composable
internal fun PersonaInspectionPanel(
    result: PersonaInspectionResult,
    selectedKeys: List<String>,
    onToggle: (PersonaAppendSuggestion) -> Unit,
) {
    if (result.conflicts.isEmpty() && result.suggestions.isEmpty()) {
        DsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.PersonSearch,
                    contentDescription = null,
                    tint = DsTheme.colors.characterAccent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.persona_gallery_inspection_clean),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
        return
    }

    if (result.conflicts.isNotEmpty()) {
        Text(
            stringResource(R.string.persona_gallery_conflict_count, result.conflicts.size),
            style = DsType.std14Strong.withReadingWeight(),
            color = DsTheme.colors.labelPrimary,
        )
        result.conflicts.forEach { conflict ->
            DsCard {
                Text(
                    personaFieldLabel(conflict.field),
                    style = DsType.caption11.withReadingWeight(),
                    color = DsTheme.colors.error,
                )
                if (conflict.fixedValue.isNotBlank()) {
                    Text(
                        stringResource(R.string.persona_gallery_fixed_value, conflict.fixedValue),
                        style = DsType.small13.withReadingWeight(),
                        color = DsTheme.colors.labelSecondary,
                    )
                }
                Text(
                    stringResource(R.string.persona_gallery_observed_value, conflict.observedValue),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelPrimary,
                )
                if (conflict.reason.isNotBlank()) {
                    Text(
                        conflict.reason,
                        style = DsType.caption11.withReadingWeight(),
                        color = DsTheme.colors.labelTertiary,
                    )
                }
            }
        }
    }

    if (result.suggestions.isNotEmpty()) {
        Text(
            stringResource(R.string.persona_gallery_suggestions_title),
            style = DsType.std14Strong.withReadingWeight(),
            color = DsTheme.colors.labelPrimary,
        )
        result.suggestions.forEach { suggestion ->
            val key = suggestionKey(suggestion)
            DsCard(onClick = { onToggle(suggestion) }) {
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
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
                            color = DsTheme.colors.accent,
                        )
                        Text(
                            suggestion.value,
                            style = DsType.small13.withReadingWeight(),
                            color = DsTheme.colors.labelPrimary,
                        )
                        if (suggestion.evidence.isNotBlank()) {
                            Text(
                                stringResource(R.string.persona_gallery_evidence, suggestion.evidence),
                                style = DsType.caption11.withReadingWeight(),
                                color = DsTheme.colors.labelTertiary,
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

