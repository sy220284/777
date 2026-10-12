package com.labteto.dshmobile.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shenyan.designsystem.components.SyStatusTag
import com.shenyan.designsystem.foundation.SyPhase
enum class DsStatus { Running, Done, Warning, Failed, Neutral }
@Deprecated("V5: use SyStatusPill")
@Composable
fun DsStatusPill(state: DsStatus, label: String, modifier: Modifier = Modifier) =
    SyStatusTag(label, when(state) {
        DsStatus.Running -> SyPhase.RUNNING
        DsStatus.Done -> SyPhase.DONE
        DsStatus.Warning -> SyPhase.BLOCKED
        DsStatus.Failed -> SyPhase.FAILED
        DsStatus.Neutral -> SyPhase.IDLE
    }, modifier)
