package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.work.LocalIndependentCodeReview
import com.labteto.dshmobile.local.work.LocalIndependentCodeReviewCoordinator
import com.labteto.dshmobile.local.work.codeReviewDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single presentation boundary for Git code reviews.
 * An already committed report is recovered without a model request; accepting never edits code.
 */
@Singleton
class LocalIndependentCodeReviewUiFacade @Inject internal constructor(
    private val reviewer: LocalIndependentCodeReviewCoordinator,
    private val tools: LocalToolsUiFacade,
) {
    internal suspend fun find(sessionId: String, repository: String, diff: LocalRemoteGitDiffUi):
        LocalIndependentCodeReview? = withContext(Dispatchers.IO) {
        reviewer.findForDiff(sessionId, repository, diff.trackingRef,
            diff.statistics, diff.patch, diff.truncated, diff.evidenceRevision)
    }

    internal suspend fun review(sessionId: String, repository: String, diff: LocalRemoteGitDiffUi):
        LocalIndependentCodeReview {
        val report = reviewer.review(sessionId, repository, diff.trackingRef,
            diff.statistics, diff.patch, diff.truncated, diff.evidenceRevision)
        val current = currentDiff(repository, diff.trackingRef, diff.includesUntrackedFiles)
        // The original report is durable, but must not be displayed as a finding for newer code.
        check(!current.truncated && report.digest == codeReviewDigest(
            repository, current.trackingRef, current.statistics, current.patch,
            current.evidenceRevision,
        )) { "审查期间代码修改已变化，旧报告已保存；请刷新差异重新审查。" }
        return report
    }

    internal suspend fun accept(sessionId: String, repository: String,
        review: LocalIndependentCodeReview, diff: LocalRemoteGitDiffUi): Boolean {
        val current = currentDiff(repository, diff.trackingRef, diff.includesUntrackedFiles)
        return reviewer.accept(sessionId, review.id, review.evidenceSequence,
            repository, current.trackingRef, current.statistics, current.patch, current.truncated,
            current.evidenceRevision)
    }

    private suspend fun currentDiff(repository: String, ref: String, includeUntracked: Boolean): LocalRemoteGitDiffUi {
        if (ref.startsWith("worktree/")) return tools.compareLocalGitForUi(repository, includeUntracked)
        val (remote, branch) = splitGitReviewTrackingRef(ref)
        return tools.compareRemoteGitForUi(repository, remote, branch)
    }
}

/** Git reference format is refs/remotes/<remote>/<nested-branch>. */
internal fun splitGitReviewTrackingRef(ref: String): Pair<String, String> {
    require(ref.startsWith("refs/remotes/")) { "不是远端跟踪分支引用" }
    val suffix = ref.removePrefix("refs/remotes/")
    val remote = suffix.substringBefore('/')
    val branch = suffix.substringAfter('/', missingDelimiterValue = "")
    require(remote.isNotBlank() && branch.isNotBlank()) { "远端分支引用不完整" }
    return remote to branch
}
