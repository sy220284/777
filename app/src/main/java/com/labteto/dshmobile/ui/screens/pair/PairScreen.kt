package com.labteto.dshmobile.ui.screens.pair

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.wallpaperSurface

/**
 * Enrol this device with a relay, by QR or by typed code.
 *
 * Scanning is the primary path because the QR carries the relay public key. Manual entry remains
 * available as an explicit secondary disclosure and shares the same Design System surface language
 * as the rest of the app instead of presenting a second full form by default.
 */
@Composable
fun PairScreen(
    onClose: () -> Unit,
    prefillUrl: String? = null,
    onPaired: (() -> Unit)? = null,
    autoScanOnOpen: Boolean = false,
    viewModel: PairViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val context = LocalContext.current
    var cameraPermissionDenied by rememberSaveable { mutableStateOf(false) }
    var showManual by rememberSaveable { mutableStateOf(prefillUrl != null) }
    BackHandler(onBack = onClose)

    LaunchedEffect(prefillUrl) {
        prefillUrl?.let {
            viewModel.prefill(it)
            showManual = true
        }
    }
    // The connection is already under way by the time this fires; the screen's job is done.
    LaunchedEffect(state.paired) {
        if (state.paired != null) {
            viewModel.acknowledgePaired()
            onPaired?.invoke() ?: onClose()
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::onScanned)
    }
    val scanPrompt = stringResource(R.string.pair_scan_prompt)
    val scanOptions = remember(scanPrompt) {
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(scanPrompt)
            .setBeepEnabled(false)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
            .setOrientationLocked(true)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionDenied = !granted
        if (granted) scanner.launch(scanOptions)
    }
    fun launchScanner() {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionDenied = false
            scanner.launch(scanOptions)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(autoScanOnOpen) {
        if (autoScanOnOpen) launchScanner()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            DsTopBar(
                title = stringResource(R.string.pair_title),
                subtitle = stringResource(R.string.pair_subtitle),
                onBack = onClose,
                backContentDescription = stringResource(R.string.common_back),
                largeTitle = true,
                modifier = Modifier.padding(
                    horizontal = DsSpacing.large,
                    vertical = DsSpacing.medium,
                ),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DsSpacing.large),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
            ) {
                DsGroupCard {
                    Text(
                        stringResource(R.string.connect_relay_banner),
                        style = DsType.small13.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                    Text(
                        stringResource(R.string.pair_scan_prompt),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                }

                DsButton(
                    text = stringResource(R.string.pair_scan),
                    onClick = { launchScanner() },
                    enabled = !state.busy,
                    variant = DsButtonVariant.Info,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (cameraPermissionDenied) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = DsShapes.block,
                        color = colors.warnTertiary,
                    ) {
                        Text(
                            stringResource(R.string.pair_camera_permission_denied),
                            style = DsType.small13.withReadingWeight(),
                            color = colors.warnLabel,
                            modifier = Modifier.padding(DsSpacing.medium),
                        )
                    }
                }

                DisclosureRow(
                    title = stringResource(R.string.pair_manual_title),
                    summary = if (showManual) {
                        stringResource(R.string.pair_url_label)
                    } else {
                        stringResource(R.string.pair_url_hint)
                    },
                    expanded = showManual,
                    onToggle = { showManual = !showManual },
                ) {
                    DsGroupCard {
                        DsTextField(
                            value = state.url,
                            onValueChange = viewModel::setUrl,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.pair_url_label)) },
                            placeholder = {
                                Text(
                                    stringResource(R.string.pair_url_hint),
                                    style = DsType.std14.withReadingWeight(),
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            shape = DsShapes.row,
                        )
                        DsTextField(
                            value = state.code,
                            onValueChange = viewModel::setCode,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.pair_code_label)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                            ),
                            shape = DsShapes.row,
                        )
                        DsTextField(
                            value = state.deviceName,
                            onValueChange = viewModel::setDeviceName,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.pair_name_label)) },
                            shape = DsShapes.row,
                        )
                        DsButton(
                            text = stringResource(
                                if (state.busy) R.string.pair_working else R.string.pair_submit,
                            ),
                            onClick = viewModel::submit,
                            enabled = !state.busy,
                            variant = DsButtonVariant.Primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                TransportNotice(state.provenance())
                state.failure?.let { PairFailureBlock(it) }
                Spacer(Modifier.height(DsSpacing.xlarge))
            }
        }
    }
}

/** What this pairing will establish about the relay before the user commits. */
@Composable
private fun TransportNotice(provenance: KeyProvenance) {
    val colors = DsTheme.colors
    val (text, warn) = when (provenance) {
        KeyProvenance.Verified -> stringResource(R.string.pair_key_verified) to false
        KeyProvenance.TrustedOnFirstUse -> stringResource(R.string.pair_key_first_use) to true
        KeyProvenance.Plaintext -> stringResource(R.string.pair_key_plaintext) to true
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = if (warn) {
            colors.warnTertiary
        } else {
            colors.wallpaperSurface(WallpaperSurfaceLevel.CARD)
        },
    ) {
        Text(
            text,
            style = DsType.small13.withReadingWeight(),
            color = if (warn) colors.warnLabel else colors.labelTertiary,
            modifier = Modifier.padding(DsSpacing.medium),
        )
    }
}

/** Why the claim did not produce a credential, in terms of what to do next. */
@Composable
private fun PairFailureBlock(failure: PairFailure) {
    val colors = DsTheme.colors
    val body = when (failure) {
        PairFailure.NotAPairingCode -> stringResource(R.string.pair_fail_not_a_code)
        is PairFailure.TooNew -> stringResource(R.string.pair_fail_too_new, failure.version)
        PairFailure.Expired -> stringResource(R.string.pair_fail_expired)
        PairFailure.Rejected -> stringResource(R.string.pair_fail_rejected)
        is PairFailure.RateLimited -> stringResource(R.string.pair_fail_rate_limited, failure.seconds)
        is PairFailure.NotARelay -> stringResource(R.string.pair_fail_not_a_relay, failure.authority)
        is PairFailure.HostRefused -> stringResource(R.string.pair_fail_host_refused, failure.authority)
        is PairFailure.Unreachable -> stringResource(R.string.pair_fail_unreachable, failure.authority)
        is PairFailure.CertificateMismatch ->
            stringResource(R.string.pair_fail_certificate, failure.authority)
        PairFailure.InvalidUrl -> stringResource(R.string.pair_fail_url)
        PairFailure.InvalidCode -> stringResource(R.string.pair_fail_code)
        PairFailure.LocalError -> stringResource(R.string.pair_fail_local)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.warnTertiary,
    ) {
        Row(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalAlignment = Alignment.Top,
        ) {
            StateDot(StateDotState.Error, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.xsmall))
            Text(body, style = DsType.small13.withReadingWeight(), color = colors.warnLabel)
        }
    }
}
