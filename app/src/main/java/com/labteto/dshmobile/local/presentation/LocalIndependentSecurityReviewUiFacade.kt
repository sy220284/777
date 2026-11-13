package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.work.LocalIndependentSecurityReviewCoordinator
import com.labteto.dshmobile.local.work.LocalSecurityReview
import com.labteto.dshmobile.local.work.codeReviewDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A separate security decision; it neither reuses a code-review acceptance nor bypasses tool approval. */
@Singleton
class LocalIndependentSecurityReviewUiFacade @Inject internal constructor(
    private val security: LocalIndependentSecurityReviewCoordinator,
    private val git: LocalIndependentCodeReviewUiFacade,
) {
    internal suspend fun find(sessionId: String, repository: String,
        diff: LocalRemoteGitDiffUi): LocalSecurityReview? = withContext(Dispatchers.IO) {
        security.find(sessionId, repository, diff.trackingRef, diff.statistics,
            diff.patch, diff.truncated, diff.evidenceRevision)
    }

    internal suspend fun review(sessionId: String, repository: String,
        diff: LocalRemoteGitDiffUi): LocalSecurityReview {
        val result = security.review(sessionId, repository, diff.trackingRef,
            diff.statistics, diff.patch, diff.truncated, diff.evidenceRevision)
        val current = git.currentDiff(repository, diff.trackingRef, diff.includesUntrackedFiles)
        check(!current.truncated && result.digest == codeReviewDigest(
            repository, current.trackingRef, current.statistics, current.patch,
            current.evidenceRevision,
        )) { "安全审核期间 Git 变更发生变化。旧报告已保存，必须重新审核。" }
        return result
    }

    internal suspend fun approve(sessionId: String, repository: String,
        review: LocalSecurityReview, diff: LocalRemoteGitDiffUi): Boolean {
        if (review.verdict != "allow") return false
        // Approval is a new read of the complete Git snapshot; no stale review can authorize new bytes.
        val current = git.currentDiff(repository, diff.trackingRef, diff.includesUntrackedFiles)
        return security.approve(sessionId, review.id, review.evidenceSequence,
            repository, current.trackingRef, current.statistics, current.patch,
            current.truncated, current.evidenceRevision)
    }
}
