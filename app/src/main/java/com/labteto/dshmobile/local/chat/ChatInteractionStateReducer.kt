package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
/** Owns deterministic hidden-state evolution after model output has been parsed. */
internal class ChatInteractionStateReducer {
    fun reduce(
        parsed: ParsedChatPostTurnPlan,
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
        persona: PersonaProfile = PersonaProfile(),
    ): ChatPostTurnPlan {
        val decoded = parsed.plan
        val rawState = parsed.rawState
        val significance = normalizeSignificance(decoded.turnSignificance)

        val agedPrevious = previous.canonicalizeLegacyCharacterState()
        val effectivePatch = ChatStatePatchAuthority.effectivePatch(
            raw = rawState,
            state = decoded.state,
            significance = significance,
            quotations = decoded.stateEvidence,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
        )
        val baseState = sanitizeState(
            value = decoded.state,
            previous = agedPrevious,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            rawState = effectivePatch,
            stageQuote = decoded.stateEvidence["dynamics.stage"].orEmpty(),
            descriptionQuote = decoded.stateEvidence["relationshipState"].orEmpty(),
            significance = significance,
        )
        val continuityState = mergeSceneAndContinuity(
            value = decoded.state,
            previous = baseState,
            rawState = effectivePatch,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
        )
        val evolvedState = applyCharacterPostTurnRuntime(
            persona, agedPrevious, continuityState, decoded.state, rawState,
            significance, userMessage, assistantMessage,
        )
        return decoded.copy(
            state = observeCharacterStateChanges(previous = agedPrevious, current = applyInteractionPerformance(
                state = applyInteractionIntent(
                    state = evolvedState,
                    previous = agedPrevious,
                    userMessage = userMessage,
                ),
                previous = agedPrevious,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
            ), quotations = decoded.stateEvidence, userMessage = userMessage, assistantMessage = assistantMessage),
            turnSignificance = significance,
        )
    }

    fun applyDeterministicInteractionState(
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState {
        val restored = previous.canonicalizeLegacyCharacterState()
        val intentState = if (userMessage.isBlank()) {
            restored
        } else {
            applyInteractionIntent(
                state = restored,
                previous = restored,
                userMessage = userMessage,
            )
        }
        return applyInteractionPerformance(
            state = intentState,
            previous = restored,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
        )
    }
    private fun applyInteractionIntent(
        state: ChatCharacterState,
        previous: ChatCharacterState,
        userMessage: String,
    ): ChatCharacterState {
        val (intent, strength) = nextInteractionIntentState(userMessage, previous)
        return state.copy(
            interactionIntent = intent,
            interactionIntentStrength = strength,
            interactionIntensity = nextInteractionIntensity(userMessage, previous),
        )
    }
    private fun applyInteractionPerformance(
        state: ChatCharacterState,
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState {
        val signals = extractInteractionPerformanceSignals(assistantMessage)
        val resetRequested = isInteractionResetIntent(userMessage)
        val assistantIntensity = if (resetRequested) 0 else assistantInitiatedInteractionIntensity(assistantMessage)
        val finalIntensity = if (resetRequested) 0 else maxOf(state.interactionIntensity, assistantIntensity)
        val finalIntent = when {
            resetRequested -> ChatInteractionIntent.NORMAL.name
            finalIntensity > 0 && state.interactionIntent == ChatInteractionIntent.NORMAL.name ->
                ChatInteractionIntent.FLIRTING.name
            else -> state.interactionIntent
        }
        val finalStrength = when {
            resetRequested -> 0
            finalIntent == ChatInteractionIntent.FLIRTING.name &&
                state.interactionIntent == ChatInteractionIntent.NORMAL.name ->
                maxOf(state.interactionIntentStrength, 2)
            else -> state.interactionIntentStrength
        }

        return state.copy(
            interactionIntent = finalIntent,
            interactionIntentStrength = finalStrength,
            interactionIntensity = finalIntensity,
            recentActionTags = mergeRecentTags(previous.recentActionTags, signals.actionTags, RECENT_ACTION_LIMIT),
            recentPoseTags = mergeRecentTags(previous.recentPoseTags, signals.poseTags, RECENT_POSE_LIMIT),
            recentVerbalTags = mergeRecentTags(previous.recentVerbalTags, signals.verbalTags, RECENT_VERBAL_LIMIT),
            recentAddressTerms = mergeRecentTags(previous.recentAddressTerms, signals.addressTerms, RECENT_ADDRESS_LIMIT),
            interactionCooldowns = emptyMap(),
        )
    }
    private fun mergeRecentTags(
        previous: List<String>,
        incoming: List<String>,
        limit: Int,
    ): List<String> {
        if (incoming.isEmpty()) return previous.takeLast(limit)
        val merged = previous.toMutableList()
        incoming.forEach { tag ->
            merged.remove(tag)
            merged += tag
        }
        return merged.takeLast(limit)
    }
    private fun sanitizeState(
        value: ChatCharacterState,
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
        rawState: JsonObject?,
        stageQuote: String,
        descriptionQuote: String,
        significance: String,
    ): ChatCharacterState {
        val rawDynamics = rawState?.get("dynamics")?.let { runCatching { it.jsonObject }.getOrNull() }
        val dynamics = if (rawDynamics == null) {
            previous.dynamics
        } else {
            sanitizeDynamics(
                value = value.dynamics,
                previous = previous.dynamics,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                raw = rawDynamics,
                stageQuote = stageQuote,
                significance = significance,
            )
        }
        // Retired user-profile data is only retained for loading saved stories.
        // The optional reply drafts rely on actual recent dialogue, not numeric personas.
        val pattern = previous.userPattern
        val requestedStage = if (rawDynamics?.containsKey("stage") == true) {
            normalizeStage(value.dynamics.stage)
        } else previous.dynamics.stage
        val relationshipDescription = when {
            dynamics.stage != previous.dynamics.stage -> stageLabel(dynamics.stage)
            requestedStage != previous.dynamics.stage -> previous.relationshipState
            rawState?.containsKey("relationshipState") == true &&
                groundedRelationshipDescription(
                    description = value.relationshipState, evidence = descriptionQuote,
                    userMessage = userMessage, assistantMessage = assistantMessage,
                ) -> value.relationshipState.trim().take(120)
            else -> previous.relationshipState
        }
        // Patch the existing authoritative snapshot. Copying the model's partially decoded
        // default state loses fields it never owned (lifeState, observations, impressions).
        val merged = previous.copy(
            behaviorTuning = previous.behaviorTuning.normalized(),
            physicalState = if (rawState?.containsKey("physicalState") == true) {
                value.physicalState.trim().take(120)
            } else previous.physicalState,
            mood = if (rawState?.containsKey("mood") == true) {
                value.mood.trim().take(240).ifBlank { "自然" }
            } else previous.mood,
            relationshipState = relationshipDescription,
            currentFocus = if (rawState?.containsKey("currentFocus") == true) {
                value.currentFocus.trim().take(240)
            } else previous.currentFocus,
            activeGoal = if (rawState?.containsKey("activeGoal") == true) {
                value.activeGoal.trim().take(240)
            } else previous.activeGoal,
            currentAgenda = if (rawState?.containsKey("currentAgenda") == true) {
                value.currentAgenda.trim().take(240)
            } else previous.currentAgenda,
            internalConflict = if (rawState?.containsKey("internalConflict") == true) {
                value.internalConflict.trim().take(240)
            } else previous.internalConflict,
            immediateConcern = if (rawState?.containsKey("immediateConcern") == true) {
                value.immediateConcern.trim().take(240)
            } else previous.immediateConcern,
            currentUserImpression = if (rawState?.containsKey("currentUserImpression") == true) {
                value.currentUserImpression.trim().take(240)
            } else previous.currentUserImpression,
            unresolvedThreads = if (rawState?.containsKey("unresolvedThreads") == true) {
                value.unresolvedThreads.asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map { it.take(200) }
                    .distinct()
                    .take(3)
                    .toList()
            } else previous.unresolvedThreads,
            // Retired personality drive scores are decoded for older stories only.
            initiative = previous.initiative,
            shareDesire = previous.shareDesire,
            dynamics = dynamics,
            evolution = previous.evolution,
            userPattern = pattern,
            scene = previous.scene,
            continuity = previous.continuity,
            interactionIntent = previous.interactionIntent,
            interactionIntentStrength = previous.interactionIntentStrength,
            interactionIntensity = previous.interactionIntensity,
            recentActionTags = previous.recentActionTags,
            recentPoseTags = previous.recentPoseTags,
            recentVerbalTags = previous.recentVerbalTags,
            recentAddressTerms = previous.recentAddressTerms,
            interactionCooldowns = previous.interactionCooldowns,
            transientAges = previous.transientAges,
            unresolvedThreadAges = previous.unresolvedThreadAges,
            updatedAt = System.currentTimeMillis(),
        )
        return merged.copy(transientAges = emptyMap(), unresolvedThreadAges = emptyMap())
    }
    private fun mergeSceneAndContinuity(
        value: ChatCharacterState,
        previous: ChatCharacterState,
        rawState: JsonObject?,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState {
        if (rawState == null) return previous
        val rawContinuity = rawState["continuity"]?.let { runCatching { it.jsonObject }.getOrNull() }
        if (rawContinuity == null) return previous

        val continuity = sanitizeChatContinuity(value.continuity, previous.continuity, rawContinuity)
        return previous.copy(
            // Hard scene state is advanced only by ChatSceneRuntime from transcript events.
            scene = previous.scene,
            continuity = continuity,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun relationshipStageEventSupports(
        proposedStage: String,
        userMessage: String,
        assistantMessage: String,
    ): Boolean {
        val conversation = listOf(userMessage, assistantMessage)
        val stageWords = when (proposedStage) {
            "AMBIGUOUS" -> Regex("暧昧|心动|喜欢你|喜欢我|对你有好感")
            "DATING" -> Regex("开始约会|正式交往|决定交往|开始交往|正在交往|在谈恋爱|谈恋爱了|正在约会|约会中")
            "COMMITTED" -> Regex("确认关系|确定关系|确定恋爱关系|在一起(?:了)?|正式在一起|已经是情侣|成为情侣|成了情侣|我们是情侣|订婚|结婚|同居")
            "CONFLICT" -> Regex("冷战|吵架|争吵|闹矛盾|发生争执")
            "COOLING" -> Regex("暂时冷静|冷淡下来|暂停关系")
            "SEPARATED" -> Regex("分手|分开了|离婚|结束关系")
            "REPAIRING" -> Regex("和好(?:了)?|复合|重新和好|修复关系")
            "FAMILIAR" -> Regex("互相认识|开始熟悉|成为朋友")
            else -> return false
        }
        return conversation.any { message ->
            message.split(Regex("[。！？!?；;\\n]")).any { clause ->
                val current = clause.trim()
                stageWords.findAll(current).any { match ->
                    val before = current.take(match.range.first).takeLast(20)
                    val after = current.drop(match.range.last + 1).take(6)
                    val firstPerson = Regex("我们|咱们|你和我|我和你|我喜欢你|你喜欢我")
                        .containsMatchIn(before + match.value + after)
                    // Check the matched event's immediate wording instead of banning the
                    // entire sentence; actual decisions may follow an earlier hypothetical.
                    val thirdParty = Regex("前任|朋友|同事|室友|他们|她们|别人")
                        .containsMatchIn(before.takeLast(10))
                    val hypothetical = Regex("如果|假如|要是|希望|计划|打算|梦见|听说")
                        .containsMatchIn(before.takeLast(8))
                    val negated = Regex("没有|还没|尚未|并未|不会|不想|不要|不是")
                        .containsMatchIn(before.takeLast(6))
                    val questioned = after.contains('吗') || after.contains('？') || after.contains('?')
                    firstPerson && !thirdParty && !hypothetical && !negated && !questioned
                }
            }
        }
    }

    /**
     * Accept a grounded, explicitly described relationship event even when its wording is
     * absent from the old fixed trigger list. The quote must occur verbatim in this turn.
     * Do not allow questions, hypotheticals, negation, third-party events or conflicting
     * explicit directions to be promoted into our relationship state.
     */
    private fun groundedRelationshipChangeQuote(
        proposedStage: String,
        rawQuote: String,
        userMessage: String,
        assistantMessage: String,
    ): Boolean {
        val quote = rawQuote.trim().take(180)
        // New free-form stage evidence must come from the user's actual utterance,
        // not an assistant's speculation or a quoted third-party report.
        val quoteIndex = userMessage.indexOf(quote)
        if (quote.length < 6 || quoteIndex < 0) return false
        val prefix = userMessage.take(quoteIndex).substringAfterLast('。').substringAfterLast('！')
            .substringAfterLast('？').takeLast(48)
        if (Regex("朋友|同事|室友|别人|他们|她们|前任|听说|说过|转述|如果|假如|要是|梦见|计划|希望").containsMatchIn(prefix)) return false
        if (!Regex("我们|咱们|你和我|我和你|我们俩|我与你|你与我|你我").containsMatchIn(quote)) return false
        if (!Regex("关系|伴侣|恋|约会|暧昧|婚|在一起|分开|冷战|和好|喜欢|相爱|分手|彼此|承诺").containsMatchIn(quote)) return false
        if (Regex("如果|假如|要是|希望|计划|打算|梦见|听说|没有|还没|尚未|并未|不会|不想|不要|不是|前任|朋友|同事|室友|他们|她们|别人").containsMatchIn(quote)) return false
        if (quote.contains('？') || quote.contains('?') || quote.endsWith("吗")) return false
        val clearlySeparated = Regex("分手|结束关系|分开了|离婚").containsMatchIn(quote)
        val clearlyRepaired = Regex("复合|和好|修复关系").containsMatchIn(quote)
        if (clearlySeparated && proposedStage != "SEPARATED") return false
        if (clearlyRepaired && proposedStage != "REPAIRING") return false
        return true
    }

    /**
     * Natural-language relation descriptions cannot bypass a failed milestone transition.
     * Fine-grained changes need a literal, non-hypothetical current-turn quotation;
     * major milestones must be represented by the separately validated stage event.
     */
    private fun groundedRelationshipDescription(
        description: String,
        evidence: String,
        userMessage: String,
        assistantMessage: String,
    ): Boolean {
        val value = description.trim()
        val quote = evidence.trim()
        if (value.isBlank() || quote.length < 4 ||
            !(userMessage.contains(quote) || assistantMessage.contains(quote))) return false
        // A random genuine quotation ("we had dinner") is not itself evidence
        // for the model to invent a new relationship conclusion.
        if (!Regex("关系|相处|彼此|互相|信任|熟悉|亲近|友谊|和解|合作|疏远|默契|隔阂|羁绊|我们之间|我们俩").containsMatchIn(quote)) return false
        val milestone = Regex("结婚|已婚|婚姻|配偶|订婚|离婚|分手|复合|同居|情侣|恋人|恋爱|交往|确定关系|在一起|暧昧期|约会中|矛盾期|冷战|分开|男朋友|女朋友")
        if (milestone.containsMatchIn(value)) return false
        val rejected = Regex("如果|假如|要是|梦见|听说|希望|打算|计划|没有|还没|尚未|并未|不是|不会|吗\\?|吗？")
        if (rejected.containsMatchIn(quote) || quote.contains('?') || quote.contains('？')) return false
        return true
    }

    private fun sanitizeDynamics(
        value: RelationshipDynamics,
        previous: RelationshipDynamics,
        userMessage: String,
        assistantMessage: String,
        raw: JsonObject,
        stageQuote: String,
        significance: String,
    ): RelationshipDynamics {
        val candidateStage = if (raw.containsKey("stage")) normalizeStage(value.stage) else previous.stage
        // Check that an explicit relationship event supports the requested target stage.
        // Unrelated words (e.g. "我的前任结婚了") cannot advance our relationship.
        val explicitStageEvidence = relationshipStageEventSupports(
            candidateStage, userMessage, assistantMessage,
        ) || (significance == "MAJOR" && groundedRelationshipChangeQuote(
            candidateStage, stageQuote, userMessage, assistantMessage,
        ))
        val stage = when {
            candidateStage == previous.stage -> previous.stage
            explicitStageEvidence -> candidateStage
            else -> previous.stage
        }

        return RelationshipDynamics(
            stage = stage,
            // Historical diagnostic scores are retained for decoding, not recomputed or fed back.
            warmth = previous.warmth,
            trust = previous.trust,
            reciprocity = previous.reciprocity,
            tension = previous.tension,
            stability = previous.stability,
            unresolvedConflict = if (raw.containsKey("unresolvedConflict")) {
                value.unresolvedConflict.trim().take(240)
            } else previous.unresolvedConflict,
            facts = if (raw.containsKey("facts")) mergeEvidence(
                previous = previous.facts,
                incoming = value.facts.filter {
                    evidenceGrounded(
                        evidence = it,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                    )
                },
                minimumConfidence = 80,
                maximumConfidence = 100,
                allowedSources = FACT_SOURCES,
                limit = 12,
            ) else previous.facts,
            hypotheses = if (raw.containsKey("hypotheses")) mergeEvidence(
                previous = previous.hypotheses,
                incoming = value.hypotheses.filter { evidenceGrounded(it, userMessage, assistantMessage) },
                minimumConfidence = 10, maximumConfidence = 85, allowedSources = FACT_SOURCES, limit = 6,
            ) else previous.hypotheses,
            // Unknowns are the full currently-open set, not append-only history. [] closes
            // answered questions; omission preserves the previous state for partial patches.
            unknowns = if (raw.containsKey("unknowns")) {
                mergeStrings(emptyList(), value.unknowns, 6, 160)
            } else previous.unknowns,
            sharedMoments = if (raw.containsKey("sharedMoments")) {
                mergeStrings(previous.sharedMoments, value.sharedMoments.filter { moment ->
                    evidenceGrounded(
                        RelationshipEvidence(text = moment, confidence = 100, source = "dialogue"),
                        userMessage,
                        assistantMessage,
                    )
                }, 8, 180)
            } else previous.sharedMoments,
            sharedObjects = if (raw.containsKey("sharedObjects")) {
                mergeStrings(previous.sharedObjects, value.sharedObjects.filter { item ->
                    evidenceGrounded(
                        RelationshipEvidence(text = item, confidence = 100, source = "dialogue"),
                        userMessage,
                        assistantMessage,
                    )
                }, 8, 160)
            } else previous.sharedObjects,
        )
    }

    private fun mergeEvidence(
        previous: List<RelationshipEvidence>,
        incoming: List<RelationshipEvidence>,
        minimumConfidence: Int,
        maximumConfidence: Int,
        allowedSources: Set<String>?,
        limit: Int,
    ): List<RelationshipEvidence> {
        val merged = linkedMapOf<String, RelationshipEvidence>()
        (previous + incoming).forEach { item ->
            val text = item.text.trim().take(220)
            if (text.isBlank()) return@forEach
            val source = item.source.trim().lowercase().take(40)
            if (allowedSources != null && source !in allowedSources) return@forEach
            val confidence = item.confidence.coerceIn(minimumConfidence, maximumConfidence)
            if (item.confidence < minimumConfidence) return@forEach
            val clean = item.copy(
                text = text,
                confidence = confidence,
                source = source,
            )
            merged[normalizeChatInteractionText(text)] = clean
        }
        return merged.values.toList().takeLast(limit)
    }

    private fun mergeStrings(
        previous: List<String>,
        incoming: List<String>,
        limit: Int,
        maxChars: Int,
    ): List<String> {
        val merged = linkedMapOf<String, String>()
        (previous + incoming).forEach { raw ->
            val text = raw.trim().take(maxChars)
            if (text.isNotBlank()) merged[normalizeChatInteractionText(text)] = text
        }
        return merged.values.toList().takeLast(limit)
    }

    private fun normalizeSignificance(raw: String): String =
        raw.trim().uppercase().takeIf { it in ALLOWED_SIGNIFICANCE } ?: "MINOR"

    private fun normalizeStage(raw: String): String {
        val stage = raw.trim().uppercase()
        return stage.takeIf { it in ALLOWED_STAGES } ?: "FAMILIAR"
    }

    private fun stageLabel(stage: String): String = when (stage) {
        "NEW" -> "刚认识"
        "FAMILIAR" -> "熟悉中"
        "AMBIGUOUS" -> "暧昧期"
        "DATING" -> "约会中"
        "COMMITTED" -> "稳定关系"
        "CONFLICT" -> "矛盾期"
        "COOLING" -> "降温期"
        "SEPARATED" -> "已分开"
        "REPAIRING" -> "修复中"
        else -> "熟悉中"
    }
private companion object {
        const val RECENT_ACTION_LIMIT = 6
        const val RECENT_POSE_LIMIT = 4
        const val RECENT_VERBAL_LIMIT = 6
        const val RECENT_ADDRESS_LIMIT = 4
        val ALLOWED_SIGNIFICANCE = setOf("NONE", "MINOR", "MAJOR")
        val FACT_SOURCES = setOf("user", "observed", "dialogue", "explicit")
        val ALLOWED_STAGES = setOf(
            "NEW", "FAMILIAR", "AMBIGUOUS", "DATING", "COMMITTED",
            "CONFLICT", "COOLING", "SEPARATED", "REPAIRING",
        )

    }
}

