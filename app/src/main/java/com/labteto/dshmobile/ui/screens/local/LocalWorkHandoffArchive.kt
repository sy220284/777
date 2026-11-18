package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.local.presentation.LocalSessionUiFacade.HandoffSearchPage
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.uicontract.UiMessageSearchCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class LocalWorkHandoffArchive(
    val matches: List<LocalHarnessMessage>,
    val loading: Boolean,
    val error: String?,
    val loadMore: (() -> Unit)?,
)

@Composable
internal fun rememberWorkHandoffArchive(
    sourceSessionId: String,
    query: String,
    enabled: Boolean,
    search: suspend (String, String, UiMessageSearchCursor?) -> HandoffSearchPage,
): LocalWorkHandoffArchive {
    val active = enabled && sourceSessionId.isNotBlank() && query.isNotBlank()
    var matches by remember(sourceSessionId, query, active) { mutableStateOf(emptyList<LocalHarnessMessage>()) }
    var next by remember(sourceSessionId, query, active) { mutableStateOf<UiMessageSearchCursor?>(null) }
    var request by remember(sourceSessionId, query, active) { mutableIntStateOf(0) }
    var loading by remember(sourceSessionId, query, active) { mutableStateOf(active) }
    var error by remember(sourceSessionId, query, active) { mutableStateOf<String?>(null) }
    LaunchedEffect(sourceSessionId, query, active, request) {
        if (!active) return@LaunchedEffect
        if (request == 0) delay(250)
        loading = true
        val cursor = if (error == null) next else null
        error = null
        try {
            val page = search(sourceSessionId, query, cursor)
            matches = (if (cursor == null) page.matches.asReversed() else matches + page.matches.asReversed())
                .distinctBy(LocalHarnessMessage::id)
            next = page.next
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "历史消息查找失败"
        } finally {
            loading = false
        }
    }
    return LocalWorkHandoffArchive(matches, loading, error,
        loadMore = if (active && !loading && (next != null || error != null)) ({ request++ }) else null)
}
