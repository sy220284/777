package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalResources
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.*

@Composable
internal fun FeedbackDialog(store: SessionStore, key: ComposerKey, messageId: String, positive: Boolean, onDismiss: () -> Unit) {
    var note by remember(key, messageId) { mutableStateOf("") }
    var current by remember(key, messageId) { mutableStateOf<MessageFeedbackItem?>(null) }
    var busy by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    suspend fun refresh() {
        val result = store.apiForHost(key.host)?.messageFeedbackList(key.sessionId)?.requireValue()
            ?: error(resources.getString(R.string.common_offline))
        if (!result.ok) error(result.error?.code.orEmpty())
        current = result.value?.items?.firstOrNull { it.messageId == messageId }
        note = current?.note.orEmpty()
        loaded = true
    }
    fun submit(remove: Boolean) {
        if (busy || !loaded) return
        busy = true; error = null
        scope.launch {
            try {
                val api = store.apiForHost(key.host) ?: error(resources.getString(R.string.common_offline))
                val failure = if (remove) {
                    val version = current?.version ?: return@launch
                    val result = api.messageFeedbackDelete(MessageFeedbackDeleteRequest(key.sessionId, messageId, version)).requireValue()
                    if (result.ok) null else result.error ?: MessageFeedbackFailure("unknown")
                } else {
                    val result = api.messageFeedbackPut(MessageFeedbackPutRequest(key.sessionId, messageId,
                        if (positive) "positive" else "negative", current?.version, note.takeIf { it.isNotBlank() })).requireValue()
                    if (result.ok) null else result.error ?: MessageFeedbackFailure("unknown")
                }
                if (failure != null) {
                    if (failure.code == "version-conflict") current = failure.current
                    error = failure.code
                } else onDismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(key, messageId) {
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { busy = false }
    }
    DsDialog(
        title = stringResource(if (positive) R.string.chat_feedback_up else R.string.chat_feedback_down),
        onDismiss = { if (!busy) onDismiss() },
    ) {
        DsTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            maxLines = 6,
            label = { Text(stringResource(R.string.feedback_note)) },
        )
        error?.let {
            Text(it, style = DsType.small13.withReadingWeight(), color = DsTheme.colors.error)
        }
        if (!loaded && !busy) {
            DsButton(
                text = stringResource(R.string.common_retry),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            refresh()
                            error = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall, Alignment.End),
        ) {
            if (current != null) {
                DsButton(
                    text = stringResource(R.string.common_remove),
                    onClick = { submit(true) },
                    enabled = !busy,
                    variant = DsButtonVariant.Danger,
                    size = DsButtonSize.Small,
                )
            }
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                enabled = !busy,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
            DsButton(
                text = stringResource(R.string.feedback_send),
                onClick = { submit(false) },
                enabled = !busy && loaded,
                size = DsButtonSize.Small,
            )
        }
    }
}
