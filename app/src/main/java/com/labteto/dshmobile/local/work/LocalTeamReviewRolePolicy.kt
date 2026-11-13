package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalAgentSpecialty

/**
 * E08: only independent, read-only review specialists may certify a reviewer
 * declaration. Role remains a method constraint; real checkpoint evidence is
 * still checked by LocalTeamVerificationController and the EventLog reducer.
 */
internal fun isQualifiedIndependentReviewRole(wireRole: String): Boolean =
    LocalAgentSpecialty.fromWire(wireRole) in setOf(
        LocalAgentSpecialty.VERIFIER,
        LocalAgentSpecialty.REVIEWER,
        LocalAgentSpecialty.QA,
        LocalAgentSpecialty.SECURITY,
    )
