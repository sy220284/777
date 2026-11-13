package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEvidenceGroundingTest {
    @Test fun userClaimsNeedMoreThanGenericSharedWords() {
        assertFalse(evidenceGrounded(
            RelationshipEvidence(text = "阿青已经答应陪我去海边", source = "user"),
            userMessage = "我今天在海边想到阿青，顺便吃了午饭",
            assistantMessage = "",
        ))
        assertTrue(evidenceGrounded(
            RelationshipEvidence(text = "阿青答应和我去海边", source = "user"),
            userMessage = "阿青答应和我去海边，周末见",
            assistantMessage = "",
        ))
    }

    @Test fun deniedUserClaimsCannotSupportOppositeFacts() {
        assertFalse(evidenceGrounded(
            RelationshipEvidence(text = "我喜欢阿青", source = "user"),
            userMessage = "我不喜欢阿青", assistantMessage = "",
        ))
        assertFalse(evidenceGrounded(
            RelationshipEvidence(text = "喜欢阿青", source = "user"),
            userMessage = "我不喜欢阿青", assistantMessage = "",
        ))
        assertTrue(evidenceGrounded(
            RelationshipEvidence(text = "我不喜欢阿青", source = "user"),
            userMessage = "我不喜欢阿青", assistantMessage = "",
        ))
    }

    @Test fun unrelatedNegationInAnotherUserClauseDoesNotEraseConfirmedPositiveFact() {
        assertTrue(evidenceGrounded(
            RelationshipEvidence(text = "对方明确说周末有空", source = "user"),
            userMessage = "她明确说周末有空，我还没约",
            assistantMessage = "",
        ))
        assertFalse(evidenceGrounded(
            RelationshipEvidence(text = "她答应和我约会", source = "user"),
            userMessage = "她说周末有空，我还没约她",
            assistantMessage = "",
        ))
        assertFalse(evidenceGrounded(
            RelationshipEvidence(text = "喜欢阿青", source = "user"),
            userMessage = "我不喜欢阿青，可我很喜欢雨天",
            assistantMessage = "",
        ))
    }

    @Test fun observedDialogueUsesActuallySpokenTextButNotUnsupportedInferences() {
        assertTrue(evidenceGrounded(
            RelationshipEvidence(text = "阿青答应今天一起散步", source = "dialogue"),
            userMessage = "今天可以出门吗？",
            assistantMessage = "阿青答应今天一起散步，去公园吧",
        ))
        assertFalse(evidenceGrounded(
            RelationshipEvidence(text = "他暗中送走了所有朋友", source = "inference"),
            userMessage = "昨天见了朋友", assistantMessage = "真好",
        ))
    }
}
