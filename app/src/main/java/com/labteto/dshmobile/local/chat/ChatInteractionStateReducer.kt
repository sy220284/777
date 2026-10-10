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

        val agedPrevious = ageTransientState(previous, userMessage)
        val baseState = if (significance == "NONE") {
            applyExplicitTransientClears(
                value = decoded.state,
                previous = agedPrevious,
                rawState = rawState,
            )
        } else {
            sanitizeState(
                value = decoded.state,
                previous = agedPrevious,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                rawState = rawState,
            )
        }
        val continuityState = mergeSceneAndContinuity(
            value = decoded.state,
            previous = baseState,
            rawState = rawState,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
        )
        val evolvedState = applyCharacterPostTurnRuntime(
            persona, agedPrevious, continuityState, decoded.state, rawState,
            significance, userMessage, assistantMessage,
        )
        return decoded.copy(
            state = applyInteractionPerformance(
                state = applyInteractionIntent(
                    state = evolvedState,
                    previous = agedPrevious,
                    userMessage = userMessage,
                ),
                previous = agedPrevious,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
            ),
            turnSignificance = significance,
        )
    }

    private fun applyExplicitTransientClears(
        value: ChatCharacterState,
        previous: ChatCharacterState,
        rawState: JsonObject?,
    ): ChatCharacterState {
        if (rawState == null) return previous

        val ages = previous.transientAges.toMutableMap()
        var changed = false

        fun shouldClear(key: String, value: String): Boolean =
            rawState.containsKey(key) && value.isBlank()

        val clearPhysicalState = shouldClear("physicalState", value.physicalState)
        val clearCurrentFocus = shouldClear("currentFocus", value.currentFocus)
        val clearActiveGoal = shouldClear("activeGoal", value.activeGoal)
        val clearCurrentAgenda = shouldClear("currentAgenda", value.currentAgenda)
        val clearInternalConflict = shouldClear("internalConflict", value.internalConflict)
        val clearImmediateConcern = shouldClear("immediateConcern", value.immediateConcern)
        val clearThreads = rawState.containsKey("unresolvedThreads") && value.unresolvedThreads.isEmpty()

        listOf(
            "physicalState" to clearPhysicalState,
            "currentFocus" to clearCurrentFocus,
            "activeGoal" to clearActiveGoal,
            "currentAgenda" to clearCurrentAgenda,
            "internalConflict" to clearInternalConflict,
            "immediateConcern" to clearImmediateConcern,
        ).forEach { (key, clear) ->
            if (clear) {
                ages.remove(key)
                changed = true
            }
        }
        if (clearThreads) changed = true
        if (!changed) return previous

        return previous.copy(
            physicalState = if (clearPhysicalState) "" else previous.physicalState,
            currentFocus = if (clearCurrentFocus) "" else previous.currentFocus,
            activeGoal = if (clearActiveGoal) "" else previous.activeGoal,
            currentAgenda = if (clearCurrentAgenda) "" else previous.currentAgenda,
            internalConflict = if (clearInternalConflict) "" else previous.internalConflict,
            immediateConcern = if (clearImmediateConcern) "" else previous.immediateConcern,
            unresolvedThreads = if (clearThreads) emptyList() else previous.unresolvedThreads,
            transientAges = ages,
            unresolvedThreadAges = if (clearThreads) emptyMap() else previous.unresolvedThreadAges,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun applyDeterministicInteractionState(
        previous: ChatCharacterState,
        userMessage: String,
        assistantMessage: String,
    ): ChatCharacterState {
        val intentState = if (userMessage.isBlank()) {
            previous
        } else {
            applyInteractionIntent(
                state = previous,
                previous = previous,
                userMessage = userMessage,
            )
        }
        return applyInteractionPerformance(
            state = intentState,
            previous = previous,
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
        val cooldowns = previous.interactionCooldowns
            .mapValues { (_, turns) -> turns - 1 }
            .filterValues { it > 0 }
            .toMutableMap()

        fun cool(prefix: String, tags: List<String>, turns: Int) {
            tags.forEach { tag -> cooldowns["$prefix:$tag"] = turns }
        }

        cool("动作", signals.actionTags, previous.behaviorTuning.cooldownTurns(ACTION_COOLDOWN_TURNS))
        cool("姿态", signals.poseTags, previous.behaviorTuning.cooldownTurns(POSE_COOLDOWN_TURNS))
        cool("话术", signals.verbalTags, previous.behaviorTuning.cooldownTurns(VERBAL_COOLDOWN_TURNS))
        cool("称呼", signals.addressTerms, previous.behaviorTuning.cooldownTurns(ADDRESS_COOLDOWN_TURNS))

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
            interactionCooldowns = if (finalIntensity == 0) emptyMap() else cooldowns.toMap(),
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
    ): ChatCharacterState {
        val rawDynamics = rawState?.get("dynamics")?.let { runCatching { it.jsonObject }.getOrNull() }
        val rawPattern = rawState?.get("userPattern")?.let { runCatching { it.jsonObject }.getOrNull() }
        val dynamics = if (rawDynamics == null) {
            previous.dynamics
        } else {
            sanitizeDynamics(
                value = value.dynamics,
                previous = previous.dynamics,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                raw = rawDynamics,
                tuning = previous.behaviorTuning,
            )
        }
        val pattern = if (rawPattern == null && userMessage.isBlank()) {
            previous.userPattern
        } else {
            sanitizeUserPattern(
                value = value.userPattern,
                previous = previous.userPattern,
                userMessage = userMessage,
                raw = rawPattern,
            )
        }
        val requestedStage = if (rawDynamics?.containsKey("stage") == true) {
            normalizeStage(value.dynamics.stage)
        } else previous.dynamics.stage
        val relationshipDescription = when {
            previous.behaviorTuning.lockRelationshipStage -> previous.relationshipState
            dynamics.stage != previous.dynamics.stage -> stageLabel(dynamics.stage)
            requestedStage != previous.dynamics.stage -> previous.relationshipState
            rawState?.containsKey("relationshipState") == true ->
                value.relationshipState.trim().take(120).ifBlank { previous.relationshipState }
            else -> previous.relationshipState
        }
        val merged = value.copy(
            behaviorTuning = previous.behaviorTuning.normalized(),
            physicalState = if (rawState?.containsKey("physicalState") == true) {
                value.physicalState.trim().take(120)
            } else previous.physicalState,
            mood = if (rawState?.containsKey("mood") == true) {
                value.mood.trim().take(80).ifBlank { previous.mood }
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
            unresolvedThreads = if (rawState?.containsKey("unresolvedThreads") == true) {
                value.unresolvedThreads.asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map { it.take(200) }
                    .distinct()
                    .take(3)
                    .toList()
            } else previous.unresolvedThreads,
            initiative = if (rawState?.containsKey("initiative") == true) {
                bounded(value.initiative, previous.initiative, 15)
            } else previous.initiative,
            shareDesire = if (rawState?.containsKey("shareDesire") == true) {
                bounded(value.shareDesire, previous.shareDesire, 15)
            } else previous.shareDesire,
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
        return resetUpdatedAges(merged, rawState)
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

    private fun ageTransientState(
        previous: ChatCharacterState,
        userMessage: String,
    ): ChatCharacterState {
        // Treat only an explicit topic-ending instruction as a reset. Common conversational
        // phrases such as "算了" or "说正事" can be quoted or refer to something else.
        val topicReset = TOPIC_RESET_REQUEST.containsMatchIn(userMessage.trim())
        val nextAges = previous.transientAges.toMutableMap()

        fun age(key: String, value: String, ttl: Int, clearOnTopicReset: Boolean = false): String {
            if (value.isBlank()) {
                nextAges.remove(key)
                return ""
            }
            if (topicReset && clearOnTopicReset) {
                nextAges.remove(key)
                return ""
            }
            val next = (nextAges[key] ?: 0) + 1
            return if (next > ttl) {
                nextAges.remove(key)
                ""
            } else {
                nextAges[key] = next
                value
            }
        }

        val nextThreadAges = previous.unresolvedThreadAges.toMutableMap()
        val threads = if (topicReset) {
            nextThreadAges.clear()
            emptyList()
        } else {
            previous.unresolvedThreads.filter { thread ->
                val key = normalizeChatInteractionText(thread)
                val next = (nextThreadAges[key] ?: 0) + 1
                if (next > previous.behaviorTuning.transientTtl(THREAD_TTL)) {
                    nextThreadAges.remove(key)
                    false
                } else {
                    nextThreadAges[key] = next
                    true
                }
            }
        }

        return previous.copy(
            physicalState = age(
                "physicalState",
                previous.physicalState,
                previous.behaviorTuning.transientTtl(3),
                clearOnTopicReset = false,
            ),
            mood = age("mood", previous.mood.takeUnless { it == "自然" }.orEmpty(), previous.behaviorTuning.moodTtl()).ifBlank { "自然" },
            currentFocus = age("currentFocus", previous.currentFocus, previous.behaviorTuning.transientTtl(3), clearOnTopicReset = true),
            currentUserImpression = previous.currentUserImpression,
            recentImpression = "",
            activeGoal = age("activeGoal", previous.activeGoal, previous.behaviorTuning.transientTtl(12)),
            currentAgenda = age("currentAgenda", previous.currentAgenda, previous.behaviorTuning.transientTtl(3), clearOnTopicReset = true),
            internalConflict = age("internalConflict", previous.internalConflict, previous.behaviorTuning.transientTtl(6)),
            immediateConcern = age("immediateConcern", previous.immediateConcern, previous.behaviorTuning.transientTtl(2), clearOnTopicReset = true),
            unresolvedThreads = threads,
            transientAges = nextAges,
            unresolvedThreadAges = nextThreadAges,
        )
    }

    private fun resetUpdatedAges(
        state: ChatCharacterState,
        rawState: JsonObject?,
    ): ChatCharacterState {
        if (rawState == null) return state
        val ages = state.transientAges.toMutableMap()

        fun reset(key: String, value: String) {
            if (!rawState.containsKey(key)) return
            if (value.isBlank()) ages.remove(key) else ages[key] = 0
        }

        reset("physicalState", state.physicalState)
        reset("mood", state.mood.takeUnless { it == "自然" }.orEmpty())
        reset("currentFocus", state.currentFocus)
        reset("activeGoal", state.activeGoal)
        reset("currentAgenda", state.currentAgenda)
        reset("internalConflict", state.internalConflict)
        reset("immediateConcern", state.immediateConcern)

        val threadAges = if (rawState.containsKey("unresolvedThreads")) {
            state.unresolvedThreads.associate { normalizeChatInteractionText(it) to 0 }
        } else {
            state.unresolvedThreadAges
        }
        return state.copy(
            transientAges = ages,
            unresolvedThreadAges = threadAges,
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

    private fun sanitizeDynamics(
        value: RelationshipDynamics,
        previous: RelationshipDynamics,
        userMessage: String,
        assistantMessage: String,
        raw: JsonObject,
        tuning: CharacterBehaviorTuning,
    ): RelationshipDynamics {
        val candidateStage = if (raw.containsKey("stage")) normalizeStage(value.stage) else previous.stage
        // Check that an explicit relationship event supports the requested target stage.
        // Unrelated words (e.g. "我的前任结婚了") cannot advance our relationship.
        val explicitStageEvidence = relationshipStageEventSupports(
            candidateStage, userMessage, assistantMessage,
        )
        val stage = when {
            tuning.lockRelationshipStage -> previous.stage
            candidateStage == previous.stage -> previous.stage
            explicitStageEvidence -> candidateStage
            else -> previous.stage
        }

        return RelationshipDynamics(
            stage = stage,
            warmth = if (raw.containsKey("warmth")) bounded(value.warmth, previous.warmth, tuning.relationshipDelta(10)) else previous.warmth,
            trust = if (raw.containsKey("trust")) bounded(value.trust, previous.trust, tuning.relationshipDelta(8)) else previous.trust,
            reciprocity = if (raw.containsKey("reciprocity")) bounded(value.reciprocity, previous.reciprocity, tuning.relationshipDelta(8)) else previous.reciprocity,
            tension = if (raw.containsKey("tension")) bounded(value.tension, previous.tension, tuning.relationshipDelta(12)) else previous.tension,
            stability = if (raw.containsKey("stability")) bounded(value.stability, previous.stability, tuning.relationshipDelta(8)) else previous.stability,
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
            unknowns = if (raw.containsKey("unknowns")) {
                mergeStrings(previous.unknowns, value.unknowns, 6, 160)
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

    private fun sanitizeUserPattern(
        value: UserChatPattern,
        previous: UserChatPattern,
        userMessage: String,
        raw: JsonObject?,
    ): UserChatPattern {
        val measuredLength = userMessage.trim().length
        val hasObservation = measuredLength > 0
        val previousWeight = previous.observedTurns.coerceIn(0, 19)
        val averageChars = if (hasObservation) {
            if (previousWeight == 0) {
                measuredLength
            } else {
                ((previous.averageMessageChars * previousWeight) + measuredLength) / (previousWeight + 1)
            }
        } else {
            previous.averageMessageChars
        }
        val measuredReplyLength = when {
            !hasObservation -> null
            averageChars < 20 -> "short"
            averageChars < 80 -> "medium"
            else -> "long"
        }
        val modelLength = value.replyLength.trim().lowercase()
            .takeIf { raw?.containsKey("replyLength") == true && it in ALLOWED_REPLY_LENGTHS }
        val length = measuredReplyLength ?: modelLength ?: previous.replyLength

        return value.copy(
            replyLength = length,
            directness = if (raw?.containsKey("directness") == true) {
                bounded(value.directness, previous.directness, 10)
            } else previous.directness,
            playfulness = if (raw?.containsKey("playfulness") == true) {
                bounded(value.playfulness, previous.playfulness, 10)
            } else previous.playfulness,
            initiative = if (raw?.containsKey("initiative") == true) {
                bounded(value.initiative, previous.initiative, 10)
            } else previous.initiative,
            emojiStyle = if (raw?.containsKey("emojiStyle") == true) {
                value.emojiStyle.trim().take(120)
            } else previous.emojiStyle,
            preferredTone = if (raw?.containsKey("preferredTone") == true) {
                value.preferredTone.trim().take(120)
            } else previous.preferredTone,
            observedTurns = if (hasObservation) (previous.observedTurns + 1).coerceAtMost(1000)
                else previous.observedTurns,
            averageMessageChars = averageChars.coerceIn(0, 2_000),
            updatedAt = System.currentTimeMillis(),
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

    private fun bounded(value: Int, previous: Int, maxDelta: Int): Int =
        value.coerceIn(previous - maxDelta, previous + maxDelta).coerceIn(0, 100)

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
        const val ACTION_COOLDOWN_TURNS = 3
        const val POSE_COOLDOWN_TURNS = 3
        const val VERBAL_COOLDOWN_TURNS = 3
        const val ADDRESS_COOLDOWN_TURNS = 2
        const val RECENT_ACTION_LIMIT = 6
        const val RECENT_POSE_LIMIT = 4
        const val RECENT_VERBAL_LIMIT = 6
        const val RECENT_ADDRESS_LIMIT = 4
        val ALLOWED_REPLY_LENGTHS = setOf("short", "medium", "long", "mixed")
        val ALLOWED_SIGNIFICANCE = setOf("NONE", "MINOR", "MAJOR")
        val FACT_SOURCES = setOf("user", "observed", "dialogue", "explicit")
        val ALLOWED_STAGES = setOf(
            "NEW", "FAMILIAR", "AMBIGUOUS", "DATING", "COMMITTED",
            "CONFLICT", "COOLING", "SEPARATED", "REPAIRING",
        )
        val TOPIC_RESET_REQUEST = Regex(
            """^(?:嗯|好|那|行|唉|算了[，,])?[，,。\s]*(?:(?:我们|咱们|我想(?:要)?|我希望|要不|不如)[\s]*)?(?:先[\s]*)?(?:换个话题|先不聊这个|不聊这个|别提这个|别再提(?:这个)?|到此为止)""",
        )
        const val THREAD_TTL = 6
    }
}

