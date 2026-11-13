package com.labteto.dshmobile.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric
import android.Manifest
import android.os.Build

/**
 * Same permission journey from first launch and Settings. Only the user's selected capabilities
 * are requested. Returning from Android Settings refreshes the *real* service and grant state.
 */
@Composable
internal fun PermissionSetupFlow(
    onFinished: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val openFailureText = stringResource(R.string.permission_setup_open_failed)
    val controller = remember(context) { DeviceCapabilitiesController(context.applicationContext) }
    val state by controller.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) } // choose, one-by-one, summary
    var chosenNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var visitedNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var skippedNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    var openError by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = PermissionSetupFeature.entries.filter { it.name in chosenNames }
    val visited = PermissionSetupFeature.entries.filter { it.name in visitedNames }.toSet()
    val skipped = PermissionSetupFeature.entries.filter { it.name in skippedNames }.toSet()

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { controller.refresh() }
    LaunchedEffect(controller) { controller.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refresh() }

    fun open(feature: PermissionSetupFeature) {
        openError = null
        try {
            when (feature) {
                PermissionSetupFeature.SCREEN_CONTROL -> controller.openAccessibilitySettings()
                PermissionSetupFeature.NOTIFICATION_READ -> controller.openNotificationAccessSettings()
                PermissionSetupFeature.TASK_ALERTS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else controller.openAppNotificationSettings()
                }
            }
            visitedNames = (visitedNames + feature.name).distinct()
        } catch (failure: RuntimeException) {
            openError = failure.localizedMessage ?: openFailureText
        }
    }
    fun startWalk(
        fromIndex: Int = 0,
        skippedForWalk: Set<PermissionSetupFeature> = skipped,
    ) {
        val next = nextPermissionSetupIndex(chosen, fromIndex, skippedForWalk) {
            permissionSetupStatus(it, state)
        }
        if (next == null) {
            page = 2
        } else {
            currentIndex = next
            page = 1
        }
    }
    LaunchedEffect(page, currentIndex, state, chosenNames, skippedNames) {
        if (page == 1) {
            val current = chosen.getOrNull(currentIndex)
            if (current == null || current in skipped ||
                permissionSetupStatus(current, state) == PermissionSetupStatus.READY) {
                startWalk(currentIndex + if (current == null) 0 else 1)
            }
        }
    }

    BackHandler {
        when (page) {
            2 -> if (chosen.isEmpty()) onBack() else startWalk(0)
            1 -> page = 0
            else -> onBack()
        }
    }
    val colors = LocalSyColors.current
    Surface(modifier = Modifier.fillMaxSize().testTag("permissionSetupFlow"), color = colors.rootSurface()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState()).padding(SyMetric.gapSection),
            verticalArrangement = Arrangement.Center,
        ) {
            when (page) {
                0 -> {
                    Text(stringResource(R.string.permission_setup_choose), style = DsType.large20.withReadingWeight())
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.permission_setup_choose_hint),
                        style = DsType.std14.withReadingWeight(), color = colors.textSecondary)
                    Spacer(Modifier.height(20.dp))
                    for (feature in PermissionSetupFeature.entries) {
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("permissionFeature_" + feature.name)
                                .clickable {
                                    chosenNames = if (feature.name in chosenNames) {
                                        chosenNames - feature.name
                                    } else {
                                        chosenNames + feature.name
                                    }
                                }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(checked = feature.name in chosenNames, onCheckedChange = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(feature.titleRes()), style = DsType.std14.withReadingWeight())
                                Text(stringResource(feature.reasonRes()), color = colors.textSecondary,
                                    style = DsType.small13.withReadingWeight())
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    SyButton(text = stringResource(R.string.permission_setup_begin), onClick = {
                        visitedNames = emptyList()
                        skippedNames = emptyList()
                        startWalk(skippedForWalk = emptySet())
                    }, modifier = Modifier.fillMaxWidth())
                    SyButton(text = stringResource(R.string.permission_setup_later), onClick = onFinished,
                        variant = SyButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
                }
                1 -> {
                    val feature = chosen.getOrNull(currentIndex)
                    if (feature != null) {
                        val status = permissionSetupStatus(feature, state, feature in visited)
                        Text(stringResource(R.string.permission_setup_step_count, currentIndex + 1, chosen.size),
                            style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(feature.titleRes()), style = DsType.large20.withReadingWeight())
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(feature.reasonRes()),
                            style = DsType.std14.withReadingWeight(), color = colors.textSecondary)
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(status.labelRes()), modifier = Modifier.testTag("permissionSetupStatus"),
                            style = DsType.std14.withReadingWeight(),
                            color = if (status == PermissionSetupStatus.READY) colors.successInk else colors.textSecondary)
                        if (status == PermissionSetupStatus.AUTHORIZED_WAITING) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.permission_setup_reconnect_hint),
                                style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
                        }
                        if (feature == PermissionSetupFeature.SCREEN_CONTROL && feature in visited &&
                            !state.accessibilityGranted && Build.VERSION.SDK_INT >= 33) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.permission_setup_restricted_hint),
                                style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
                        }
                        if (openError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.permission_setup_open_failed) + "：" + openError,
                                style = DsType.small13.withReadingWeight(), color = colors.dangerInk)
                        }
                        Spacer(Modifier.height(24.dp))
                        SyButton(
                            text = stringResource(if (feature in visited)
                                R.string.permission_setup_open_again else R.string.permission_setup_open),
                            onClick = { open(feature) },
                            modifier = Modifier.fillMaxWidth().testTag("permissionSetupOpen"),
                        )
                        if (feature == PermissionSetupFeature.SCREEN_CONTROL && feature in visited &&
                            !state.accessibilityGranted && Build.VERSION.SDK_INT >= 33) {
                            SyButton(
                                text = stringResource(R.string.permission_setup_app_info),
                                onClick = {
                                    openError = null
                                    try { controller.openAppDetails() }
                                    catch (failure: RuntimeException) {
                                        openError = failure.localizedMessage
                                            ?: openFailureText
                                    }
                                },
                                variant = SyButtonVariant.Outline,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        SyButton(text = stringResource(R.string.permission_setup_refresh),
                            onClick = controller::refresh, variant = SyButtonVariant.Outline,
                            modifier = Modifier.fillMaxWidth())
                        SyButton(text = stringResource(R.string.permission_setup_skip),
                            onClick = {
                                skippedNames = (skippedNames + feature.name).distinct()
                                startWalk(currentIndex + 1)
                            }, variant = SyButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
                    }
                }
                else -> {
                    Text(stringResource(R.string.permission_setup_summary), style = DsType.large20.withReadingWeight())
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.permission_setup_summary_hint),
                        style = DsType.std14.withReadingWeight(), color = colors.textSecondary)
                    Spacer(Modifier.height(16.dp))
                    for ((index, feature) in chosen.withIndex()) {
                        val status = permissionSetupStatus(feature, state, feature in visited)
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            currentIndex = index
                            page = 1
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(feature.titleRes()), modifier = Modifier.weight(1f),
                                style = DsType.std14.withReadingWeight())
                            Text(stringResource(status.labelRes()),
                                style = DsType.small13.withReadingWeight(),
                                color = if (status == PermissionSetupStatus.READY) colors.successInk
                                    else colors.textSecondary)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    SyButton(text = stringResource(R.string.permission_setup_finish), onClick = onFinished,
                        modifier = Modifier.fillMaxWidth())
                    if (chosen.any { permissionSetupStatus(it, state) != PermissionSetupStatus.READY }) {
                        SyButton(text = stringResource(R.string.permission_setup_retry),
                            onClick = { startWalk() }, variant = SyButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private fun PermissionSetupFeature.titleRes(): Int = when (this) {
    PermissionSetupFeature.SCREEN_CONTROL -> R.string.permission_setup_screen
    PermissionSetupFeature.NOTIFICATION_READ -> R.string.permission_setup_read_notifications
    PermissionSetupFeature.TASK_ALERTS -> R.string.permission_setup_task_alerts
}

private fun PermissionSetupFeature.reasonRes(): Int = when (this) {
    PermissionSetupFeature.SCREEN_CONTROL -> R.string.permission_setup_screen_hint
    PermissionSetupFeature.NOTIFICATION_READ -> R.string.permission_setup_read_notifications_hint
    PermissionSetupFeature.TASK_ALERTS -> R.string.permission_setup_task_alerts_hint
}

private fun PermissionSetupStatus.labelRes(): Int = when (this) {
    PermissionSetupStatus.READY -> R.string.permission_setup_ready
    PermissionSetupStatus.AUTHORIZED_WAITING -> R.string.permission_setup_waiting
    PermissionSetupStatus.VISITED_NOT_GRANTED -> R.string.permission_setup_visited
    PermissionSetupStatus.NOT_GRANTED -> R.string.permission_setup_not_granted
}
