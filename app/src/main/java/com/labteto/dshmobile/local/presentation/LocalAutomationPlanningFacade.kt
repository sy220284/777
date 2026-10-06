package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.automation.AutomationPlanDraft
import com.labteto.dshmobile.local.automation.AutomationPlanningService
import javax.inject.Inject
import javax.inject.Singleton

/** Automation planning presentation boundary; planner internals remain Automation-owned. */
@Singleton
class LocalAutomationPlanningFacade @Inject constructor(
    private val planning: AutomationPlanningService,
) {
    internal val revisions get() = planning.revisions
    internal fun currentRevision() = planning.currentRevision()
    internal suspend fun suggestions() = planning.suggestions()
    internal suspend fun plan(input: String): AutomationPlanDraft = planning.plan(input)
    internal fun isCurrent(draft: AutomationPlanDraft) = planning.isCurrent(draft)
    internal fun isCurrent(suggestions: com.labteto.dshmobile.local.automation.AutomationSuggestionSet) = planning.isCurrent(suggestions)
}
