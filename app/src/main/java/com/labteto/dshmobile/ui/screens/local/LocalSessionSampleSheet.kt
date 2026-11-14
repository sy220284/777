package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LOCAL_SESSION_SAMPLE_MAX_PROMPT_BYTES
import com.labteto.dshmobile.local.model.LocalConversationSampleResult
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A single model sampling action, independent of the real conversation and of Work execution. */
@Composable
internal fun LocalSessionSampleSheet(
    sessionId: String,
    onSample: suspend (String, String) -> LocalConversationSampleResult,
    onDismiss: () -> Unit,
) {
    val colors = LocalSyColors.current
    val scope = rememberCoroutineScope()
    var prompt by rememberSaveable(sessionId) { mutableStateOf("") }
    var result by remember(sessionId) { mutableStateOf<LocalConversationSampleResult?>(null) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    var running by remember(sessionId) { mutableStateOf(false) }
    var job by remember(sessionId) { mutableStateOf<Job?>(null) }
    val valid = prompt.isNotBlank() &&
        prompt.toByteArray(Charsets.UTF_8).size <= LOCAL_SESSION_SAMPLE_MAX_PROMPT_BYTES

    SyResponsiveSheet(
        title = stringResource(R.string.local_session_sample_title),
        onDismiss = {
            job?.cancel()
            onDismiss()
        },
        scrollable = true,
    ) {
        Text(
            stringResource(R.string.local_session_sample_hint),
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
        SyTextField(
            value = prompt,
            onValueChange = { value ->
                prompt = value
                error = null
                result = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.local_session_sample_prompt)) },
            minLines = 3,
            maxLines = 6,
            enabled = !running,
        )
        Text(
            "${prompt.toByteArray(Charsets.UTF_8).size}/$LOCAL_SESSION_SAMPLE_MAX_PROMPT_BYTES 字节",
            style = DsType.caption11.withReadingWeight(),
            color = if (valid || prompt.isEmpty()) colors.textTertiary else colors.dangerInk,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            SyButton(
                text = stringResource(if (running) R.string.local_session_sample_cancel else R.string.local_session_sample_start),
                onClick = {
                    if (running) {
                        job?.cancel()
                        running = false
                        error = "采样已取消"
                    } else if (valid) {
                        val submitted = prompt
                        result = null
                        error = null
                        running = true
                        job = scope.launch {
                            try {
                                result = onSample(sessionId, submitted)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "采样失败，请检查模型及当前会话状态"
                            } finally {
                                running = false
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = running || valid,
                size = SyButtonSize.Small,
                variant = if (running) SyButtonVariant.Outline else SyButtonVariant.Primary,
            )
        }
        error?.let {
            Text(it, style = DsType.small13.withReadingWeight(), color = colors.dangerInk)
        }
        result?.let { preview ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = SyMetric.gapMd),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                Text(
                    stringResource(R.string.local_session_sample_result),
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.text,
                )
                Text(preview.content, style = DsType.std14.withReadingWeight(), color = colors.text)
                Text(
                    if (preview.usageReported)
                        "${preview.model} · 输入 ${preview.inputTokens} / 输出 ${preview.outputTokens} tokens；费用在用量明细中查询"
                    else stringResource(R.string.local_session_sample_usage_unreported),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                )
            }
        }
    }
}
