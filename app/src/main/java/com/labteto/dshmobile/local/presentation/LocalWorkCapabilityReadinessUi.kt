package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadiness
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadinessProjector

/**
 * UI boundary for work-handoff readiness hints. The Feature tools layer owns policy,
 * while presentation exposes its read-only projection to the Compose surface.
 */
internal fun projectWorkCapabilityReadiness(
    task: String,
    githubConfigured: Boolean?,
    networkSearchEnabled: Boolean,
): List<LocalTaskCapabilityReadiness> =
    LocalTaskCapabilityReadinessProjector.project(task, githubConfigured, networkSearchEnabled)
