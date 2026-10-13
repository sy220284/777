package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
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
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.shenyan.designsystem.components.SyCategoryRow
import com.shenyan.designsystem.components.SyTextField

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
            excerpt = body.replace(Regex("\\s+"), " ").take(150),
            fromUser = message.role == "user",
        )
        if (results.size >= limit) break
    }
    return results
}

@Composable
internal fun V6LoadedConversationSearchSheet(
    transcript: List<LocalTranscriptItem>,
    onSelect: (V6LoadedMessageSearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, transcript) { v6SearchLoadedConversation(transcript, query) }
    val colors = DsTheme.colors
    SyResponsiveSheet(
        title = stringResource(R.string.sy_v6_search_messages),
        subtitle = stringResource(R.string.sy_v6_search_loaded_scope),
        onDismiss = onDismiss,
    ) {
        SyTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.sy_v6_search_message_hint)) },
            leadingIcon = {
                Icon(SyEntryIcons.Search, contentDescription = null, tint = colors.labelTertiary)
            },
            shape = DsShapes.block,
            singleLine = true,
        )
        when {
            query.isBlank() -> Text(
                stringResource(R.string.sy_v6_search_message_hint),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            results.isEmpty() -> Text(
                stringResource(R.string.sy_v6_search_no_messages),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
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
    }
}
