package com.labteto.dshmobile.ui.screens.settings


import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.ui.shenyan.SyMenu
import com.labteto.dshmobile.ui.shenyan.SyValueRow
import com.labteto.dshmobile.ui.shenyan.SyMenuItem

@Composable
internal fun AgentWorkerModelSettingRow(
    local: LocalHarnessSettingsState,
    onSelect: (String?) -> Unit,
) {
    val selectedWorker = local.modelSelection.workerProfile
    val autoLabel = stringResource(R.string.advanced_worker_model_auto)
    SyMenu(
        anchor = {
            SyValueRow(
                label = stringResource(R.string.advanced_worker_model),
                value = selectedWorker?.let { profile ->
                    profile.displayName?.takeIf(String::isNotBlank)
                        ?: (profile.provider + " · " + profile.model).trim(' ', '·')
                } ?: autoLabel,
                hint = stringResource(R.string.advanced_worker_model_hint),
            )
        },
        items = listOf(SyMenuItem(autoLabel) { onSelect(null) }) +
            local.modelProfiles.map { profile ->
                SyMenuItem(
                    profile.displayName?.takeIf(String::isNotBlank)
                        ?: (profile.provider + " · " + profile.model).trim(' ', '·'),
                ) {
                    onSelect(profile.id)
                }
            },
    )
}
