package com.labteto.dshmobile.ui.screens.settings

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import com.shenyan.designsystem.components.SyRadioControl
import com.labteto.dshmobile.ui.shenyan.SySlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalModelPerformanceControls
import com.labteto.dshmobile.local.model.ModelReasoningCeiling
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlin.math.roundToInt

/**
 * One global upper-bound page. Values are not per-session selections:
 * existing Chat/Work preferences stay intact and become effective again when raised.
 */
@Composable
internal fun ModelPerformanceSettingsPage() {
    val context = LocalContext.current
    remember(context) { LocalModelPerformanceControls.attach(context); true }
    val limits by LocalModelPerformanceControls.state.collectAsStateWithLifecycle()
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current

    SettingsSeamlessGroup(stringResource(R.string.settings_model_performance_reasoning)) {
        Text(
            stringResource(R.string.settings_model_performance_reasoning_hint),
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
        )
        val choices = listOf(
            ModelReasoningCeiling.CUSTOM to R.string.settings_model_performance_custom,
            ModelReasoningCeiling.DEFAULT to R.string.local_composer_reasoning_default,
            ModelReasoningCeiling.FAST to R.string.local_composer_reasoning_off,
            ModelReasoningCeiling.LOW to R.string.local_composer_reasoning_low,
            ModelReasoningCeiling.DEEP to R.string.local_composer_reasoning_high,
            ModelReasoningCeiling.MAX to R.string.local_composer_reasoning_max,
        )
        choices.forEach { (ceiling, label) ->
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(DsShapes.row)
                    .background(if (limits.reasoningCeiling == ceiling) colors.selected.copy(alpha = 0.45f)
                        else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = limits.reasoningCeiling == ceiling, role = Role.RadioButton,
                        onClick = { LocalModelPerformanceControls.setReasoningCeiling(ceiling) })
                    .padding(horizontal = SyMetric.gapMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                SyRadioControl(
                    selected = limits.reasoningCeiling == ceiling,
                    onClick = null,
                )
                Text(stringResource(label), style = DsType.small13.withReadingWeight(), color = colors.text)
            }
        }
        Text(
            stringResource(R.string.settings_model_performance_restoration),
            style = DsType.caption11.withReadingWeight(), color = colors.textSecondary,
        )
    }

    SettingsSeamlessGroup(stringResource(R.string.settings_model_performance_temperature)) {
        Text(
            stringResource(R.string.settings_model_performance_temperature_hint),
            style = DsType.small13.withReadingWeight(), color = colors.textSecondary,
        )
        var draft by remember(limits.temperatureCeiling) {
            mutableFloatStateOf(limits.temperatureCeiling.toFloat())
        }
        val rounded = (draft * 20).roundToInt() / 20f
        Text(
            stringResource(R.string.settings_model_performance_temperature_value, rounded),
            style = DsType.std14Strong.withReadingWeight(), color = colors.text,
        )
        SySlider(
            value = draft,
            onValueChange = { draft = (it * 20).roundToInt() / 20f },
            onValueChangeFinished = {
                LocalModelPerformanceControls.setTemperatureCeiling((draft * 20).roundToInt() / 20.0)
            },
            valueRange = 1f..2f,
            steps = 19,
            hapticSegments = 20,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1.00", style = DsType.caption11.withReadingWeight(), color = colors.textSecondary)
            Text("2.00", style = DsType.caption11.withReadingWeight(), color = colors.textSecondary)
        }
    }
}
