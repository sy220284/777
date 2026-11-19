package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.shape.RoundedCornerShape

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.visibleAssistantText
import com.labteto.dshmobile.local.presentation.editableChatUserText
import com.labteto.dshmobile.ui.components.SyEntryIcons
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.SyType
import com.shenyan.designsystem.components.SyCategoryRow
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.uicontract.UiMessageSearchCursor
import com.labteto.dshmobile.uicontract.UiMessageSearchReceipt
import com.labteto.dshmobile.uicontract.UiMessageRole
import kotlinx.coroutines.delay

/** Design 33 phone search: results are exactly the dialogue rows already loaded in this session.
 * A message ID and its transcript index stay together; hidden reasoning and tool text never leak
 * into search results. This is intentionally not labelled as cross-session full-text search.
 */
internal data class V6LoadedMessageSearchHit(
    val messageId: String,
    val transcriptIndex: Int,
    val excerpt: String,
    val fromUser: Boolean,
)

internal fun v6SearchLoadedConversation(
    transcript: List<LocalTranscriptItem>,
    query: String,
    limit: Int = 40,
): List<V6LoadedMessageSearchHit> {
    val needle = query.trim()
    if (needle.isBlank() || limit <= 0) return emptyList()
    val results = ArrayList<V6LoadedMessageSearchHit>()
    for (index in transcript.indices.reversed()) {
        val message = (transcript[index] as? LocalTranscriptItem.Message)?.message ?: continue
        val body = when (message.role) {
            "user" -> editableChatUserText(message)
            "assistant" -> visibleAssistantText(message.content)
            else -> continue
        }.trim()
        if (!body.contains(needle, ignoreCase = true)) continue
        results += V6LoadedMessageSearchHit(
            messageId = message.id,
            transcriptIndex = index,
            excerpt = messageSearchExcerpt(body, needle),
            fromUser = message.role == "user",
        )
        if (results.size >= limit) break
    }
    return results
}

private fun messageSearchExcerpt(body: String, query: String): String {
    val match = body.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val start = (match - 50).coerceAtLeast(0)
    val end = minOf(body.length, maxOf(start + 150, match + query.length))
    return (if (start > 0) "…" else "") +
        body.substring(start, end).replace(Regex("\\s+"), " ") +
        if (end < body.length) "…" else ""
}

@Composable
internal fun V6LoadedConversationSearchSheet(
    transcript: List<LocalTranscriptItem>,
    onSelect: (V6LoadedMessageSearchHit) -> Unit,
    onDismiss: () -> Unit,
    onSearchAll: () -> Unit = {},
    search: (suspend (String, UiMessageSearchCursor?) -> UiMessageSearchReceipt)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val localHits = remember(query, transcript) {
        v6SearchLoadedConversation(transcript, query, limit = transcript.size)
    }
    var archiveHits by remember(query) { mutableStateOf(emptyList<V6LoadedMessageSearchHit>()) }
    var next by remember(query) { mutableStateOf<UiMessageSearchCursor?>(null) }
    var request by remember(query) { mutableStateOf(0) }
    var searching by remember(query) { mutableStateOf(search != null && query.isNotBlank()) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    val results = (localHits + archiveHits).distinctBy(V6LoadedMessageSearchHit::messageId)
    LaunchedEffect(query, request) {
        if (search == null || query.isBlank()) return@LaunchedEffect
        if (request == 0) delay(250)
        searching = true
        val from = if (error == null) next else null
        error = null
        when (val receipt = search(query, from)) {
            is UiMessageSearchReceipt.Available -> {
                val result = receipt.result
                val hits = result.page.messages.asReversed()
                    .filter { it.id in result.hitIds }
                    .map { message ->
                        V6LoadedMessageSearchHit(message.id, -1,
                            messageSearchExcerpt(message.text, query.trim()), message.role == UiMessageRole.USER)
                    }
                archiveHits = if (from == null) hits else archiveHits + hits
                next = result.nextCursor
            }
            is UiMessageSearchReceipt.Failed -> error = receipt.message.orEmpty()
        }
        searching = false
    }
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    SyResponsiveSheet(
        title = stringResource(R.string.sy_v6_search_messages),
        subtitle = stringResource(if (search == null) R.string.sy_v6_search_loaded_scope else R.string.sy_v6_search_archive_scope),
        onDismiss = onDismiss,
    ) {
        SyTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.sy_v6_search_message_hint)) },
            leadingIcon = {
                Icon(SyEntryIcons.Search, contentDescription = null, tint = colors.textTertiary)
            },
            shape = RoundedCornerShape(SyMetric.radiusCard),
            singleLine = true,
        )
        when {
            query.isBlank() -> Text(
                stringResource(R.string.sy_v6_search_message_hint),
                style = SyType.small13,
                color = colors.textSecondary,
            )
            results.isEmpty() && !searching && error == null -> Text(
                stringResource(R.string.sy_v6_search_no_messages),
                style = SyType.small13,
                color = colors.textSecondary,
            )
            results.isNotEmpty() -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(results, key = V6LoadedMessageSearchHit::messageId) { hit ->
                    SyCategoryRow(
                        icon = if (hit.fromUser) SyEntryIcons.Persona else SyEntryIcons.ChatStyle,
                        title = hit.excerpt,
                        subtitle = stringResource(if (hit.fromUser) R.string.sy_v6_search_user
                            else R.string.sy_v6_search_assistant),
                        onClick = { onSelect(hit) },
                    )
                }
            }
        }
        if (searching) Text(stringResource(R.string.common_loading), style = SyType.small13)
        error?.let { Text(it.ifBlank { stringResource(R.string.sy_v6_search_archive_error) }, style = SyType.small13) }
        if (!searching && (next != null || error != null)) SyButton(
            text = stringResource(if (error != null) R.string.common_retry else R.string.sy_v6_search_older),
            onClick = { request++ },
            modifier = Modifier.fillMaxWidth(),
            variant = SyButtonVariant.Ghost,
        )
        SyButton(
            text = stringResource(R.string.sy_v6_search_all),
            onClick = onSearchAll,
            modifier = Modifier.fillMaxWidth(),
            variant = SyButtonVariant.Outline,
        )
    }
}


/**
 * Isolate search state, sheet rendering and scroll targeting from the very large
 * conversation composable, avoiding ART register-verifier pressure on Android 16.
 */
internal class V6LoadedConversationSearchState {
    var open by mutableStateOf(false)
}

@Composable
internal fun rememberV6LoadedConversationSearchState(sessionId: String): V6LoadedConversationSearchState =
    remember(sessionId) { V6LoadedConversationSearchState() }

@Composable
internal fun V6LoadedConversationSearchHost(
    controller: V6LoadedConversationSearchState,
    transcript: List<LocalTranscriptItem>,
    sessionId: String,
    search: suspend (String, String, UiMessageSearchCursor?) -> UiMessageSearchReceipt,
    onSelectMessage: (String) -> Unit,
    visible: Boolean,
    onSearchAll: () -> Unit,
) {
    if (controller.open && visible) {
        V6LoadedConversationSearchSheet(
            transcript = transcript,
            onSelect = { hit ->
                controller.open = false
                onSelectMessage(hit.messageId)
            },
            onDismiss = { controller.open = false },
            search = { query, cursor -> search(sessionId, query, cursor) },
            onSearchAll = {
                controller.open = false
                onSearchAll()
            },
        )
    }
}
