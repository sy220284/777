package com.labteto.dshmobile.local.chat

import kotlinx.serialization.Serializable

/**
 * Deterministic scene event extracted from the durable user/assistant transcript.
 *
 * The transcript remains the source of truth. These events are a compact replay aid: they describe
 * only state transitions that are explicit enough for code to apply without asking the model to
 * rewrite the whole scene.
 */
@Serializable
data class ChatSceneEvent(
    val sequence: Long = 0L,
    val kind: ChatSceneEventKind = ChatSceneEventKind.LOCATION,
    val actor: String = "",
    val from: String = "",
    val to: String = "",
    val evidence: String = "",
)

@Serializable
enum class ChatSceneEventKind {
    LOCATION,
    TIME,
}

internal data class ChatContinuityViolation(
    val code: String,
    val expected: String,
    val observed: String,
)

internal data class ChatContinuityCheck(
    val events: List<ChatSceneEvent> = emptyList(),
    val violations: List<ChatContinuityViolation> = emptyList(),
) {
    val accepted: Boolean get() = violations.isEmpty()
}

/**
 * Small deterministic runtime for hard scene continuity.
 *
 * It deliberately handles only high-confidence physical transitions. Ambiguous narrative details
 * remain in raw dialogue / soft memory rather than being promoted into hard state.
 */
internal object ChatSceneRuntime {
    fun extractTurnEvents(
        previous: ChatSceneState,
        userMessage: String,
        assistantMessage: String,
        sequence: Long,
    ): List<ChatSceneEvent> {
        val userEvents = extractMessageEvents(
            previous = previous,
            text = userMessage,
            sequence = sequence,
            actor = "用户",
            allowSceneCut = false,
        )
        val afterUser = reduce(previous, userEvents)
        val assistantEvents = extractMessageEvents(
            previous = afterUser,
            text = assistantMessage,
            sequence = sequence,
            actor = "角色",
            allowSceneCut = true,
        )
        return (userEvents + assistantEvents).distinctBy { event ->
            listOf(event.sequence.toString(), event.kind.name, normalize(event.to), event.actor)
        }
    }

    fun reduce(
        previous: ChatSceneState,
        events: List<ChatSceneEvent>,
    ): ChatSceneState {
        var scene = previous
        var hardTransitionApplied = false
        events.sortedBy(ChatSceneEvent::sequence).forEach { event ->
            when (event.kind) {
                ChatSceneEventKind.LOCATION -> {
                    val target = event.to.trim().take(120)
                    if (target.isNotBlank() && !sameLocation(target, scene.location)) {
                        scene = scene.copy(location = target)
                        hardTransitionApplied = true
                    }
                }
                ChatSceneEventKind.TIME -> {
                    val target = event.to.trim().take(80)
                    if (target.isNotBlank() && target != scene.sceneTime) {
                        scene = scene.copy(sceneTime = target)
                        hardTransitionApplied = true
                    }
                }
            }
        }
        return scene.copy(
            // These legacy fields have no deterministic event source yet. Once a hard transition
            // occurs, carrying them forward would make stale positions/actions look authoritative.
            participants = if (hardTransitionApplied) emptyList() else scene.participants,
            positions = if (hardTransitionApplied) emptyList() else scene.positions,
            activeActions = if (hardTransitionApplied) emptyList() else scene.activeActions,
            keyObjects = if (hardTransitionApplied) emptyList() else scene.keyObjects,
            currentEvent = "",
            lastSceneChange = "",
        )
    }

    /**
     * Validate a candidate assistant reply before it becomes a durable assistant/message.
     *
     * A reply may move the scene when it contains an explicit completed movement or a clear
     * narrative cut. A bare presence assertion at another location is rejected.
     */
    fun inspectReply(
        previous: ChatSceneState,
        userMessage: String,
        assistantMessage: String,
    ): ChatContinuityCheck {
        if (assistantMessage.isBlank()) return ChatContinuityCheck()

        val userEvents = extractMessageEvents(
            previous = previous,
            text = userMessage,
            sequence = 0L,
            actor = "用户",
            allowSceneCut = false,
        )
        val afterUser = reduce(previous, userEvents)
        val assistantEvents = extractMessageEvents(
            previous = afterUser,
            text = assistantMessage,
            sequence = 0L,
            actor = "角色",
            allowSceneCut = true,
        )
        val afterAssistant = reduce(afterUser, assistantEvents)

        val explicitTargets = presenceAssertions(assistantMessage)
        val acceptedTargets = assistantEvents
            .filter { it.kind == ChatSceneEventKind.LOCATION }
            .map(ChatSceneEvent::to)

        val violations = explicitTargets.mapNotNull { target ->
            val grounded = sameLocation(target, afterUser.location) ||
                acceptedTargets.any { accepted -> sameLocation(target, accepted) } ||
                sameLocation(target, afterAssistant.location)
            if (grounded) {
                null
            } else {
                ChatContinuityViolation(
                    code = "UNBRIDGED_LOCATION_CHANGE",
                    expected = afterUser.location,
                    observed = target,
                )
            }
        }.distinctBy { it.code to normalize(it.observed) }

        return ChatContinuityCheck(
            events = (userEvents + assistantEvents).distinctBy { event ->
                listOf(event.kind.name, normalize(event.to), event.actor)
            },
            violations = violations,
        )
    }

    private fun extractMessageEvents(
        previous: ChatSceneState,
        text: String,
        sequence: Long,
        actor: String,
        allowSceneCut: Boolean,
    ): List<ChatSceneEvent> {
        if (text.isBlank()) return emptyList()
        val compact = text.replace("\r", "")
        val events = mutableListOf<ChatSceneEvent>()

        TIME_ADVANCE_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(compact)?.value?.trim()
        }?.let { time ->
            events += ChatSceneEvent(
                sequence = sequence,
                kind = ChatSceneEventKind.TIME,
                actor = actor,
                from = previous.sceneTime,
                to = normalizeTime(time),
                evidence = time.take(120),
            )
        }

        val movement = MOVEMENT_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(compact)?.let { match ->
                sanitizeLocation(match.groups["target"]?.value.orEmpty())
                    .takeIf(String::isNotBlank)
                    ?.let { target -> target to match.value }
            }
        }
        if (movement != null) {
            val (target, evidence) = movement
            events += ChatSceneEvent(
                sequence = sequence,
                kind = ChatSceneEventKind.LOCATION,
                actor = actor,
                from = previous.location,
                to = target,
                evidence = evidence.take(160),
            )
        } else if (allowSceneCut && hasNarrativeCut(compact)) {
            presenceAssertions(compact).firstOrNull()?.let { target ->
                if (!sameLocation(target, previous.location)) {
                    events += ChatSceneEvent(
                        sequence = sequence,
                        kind = ChatSceneEventKind.LOCATION,
                        actor = actor,
                        from = previous.location,
                        to = target,
                        evidence = compact.take(160),
                    )
                }
            }
        } else if (previous.location.isBlank()) {
            // Cold replay may have no checkpoint before the retained prefix. A high-confidence
            // present-location assertion is safe to use as the initial baseline only while the
            // hard location is still unknown.
            presenceAssertions(compact).firstOrNull()?.let { target ->
                events += ChatSceneEvent(
                    sequence = sequence,
                    kind = ChatSceneEventKind.LOCATION,
                    actor = actor,
                    from = "",
                    to = target,
                    evidence = compact.take(160),
                )
            }
        }

        return events
    }

    private fun presenceAssertions(text: String): List<String> =
        PRESENCE_PATTERNS.asSequence()
            .flatMap { regex -> regex.findAll(text) }
            .mapNotNull { match ->
                sanitizeLocation(match.groups["target"]?.value.orEmpty())
                    .takeIf(String::isNotBlank)
            }
            .filterNot(::looksLikeAbstractState)
            .distinctBy(::normalize)
            .take(4)
            .toList()

    private fun hasNarrativeCut(text: String): Boolean =
        NARRATIVE_CUT.containsMatchIn(text) ||
            TIME_ADVANCE_PATTERNS.any { it.containsMatchIn(text) }

    private fun sameLocation(a: String, b: String): Boolean {
        val left = normalizeLocation(a)
        val right = normalizeLocation(b)
        if (left.isBlank() || right.isBlank()) return false
        if (left == right) return true
        return left.length >= 2 && right.length >= 2 &&
            (left.contains(right) || right.contains(left))
    }

    private fun sanitizeLocation(raw: String): String =
        raw.trim()
            .trim('，', '。', '！', '？', '；', '：', ',', '.', '!', '?', ';', ':')
            .replace(Regex("""^(?:这个|那个|这座|那座|这间|那间|一间|一处)"""), "")
            .replace(Regex("""(?:里面|里边|内部|外面)$"""), "")
            .replace(Regex("""(?<=[间室房厅院馆店楼车])里$"""), "")
            .replace(Regex("""(?:床边|桌边|桌旁|窗边|门边|门旁|角落|中央|中间)$"""), "")
            // Movement utterances often end in a sentence-final particle ("回房间吧"). Keep
            // that conversational particle out of the canonical hard location so repeating the
            // same move cannot manufacture a second location transition.
            .replace(Regex("""(?<=[间室房厅院馆店楼车园场站舍屋堂宫宅])(?:吧|呢|啊|呀|啦|了)$"""), "")
            .trim()
            .take(120)

    private fun normalizeLocation(value: String): String =
        normalize(sanitizeLocation(value))
            .replace(Regex("""(?:这里|那里|这儿|那儿)$"""), "")

    private fun looksLikeAbstractState(value: String): Boolean {
        val normalized = normalize(value)
        return normalized.isBlank() ||
            ABSTRACT_LOCATION_WORDS.any(normalized::startsWith)
    }

    private fun normalizeTime(value: String): String =
        value.trim().replace(Regex("""\s+"""), "").take(80)

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】]+"""), "")

    private val MOVEMENT_PATTERNS = listOf(
        // Narrative grammar such as “回到房间后，……” uses 后 as a transition marker, not as
        // part of the destination. Keep this ahead of the generic movement matcher.
        Regex("""(?:走进|进入|回到|回了|来到|走到|跑到|赶到|抵达|到了|进了|返回)(?<target>[^，。！？；：\n]{1,24}?)(?:之后|以后|后)(?=[，。！？；：\n])"""),
        Regex("""(?:走进|进入|回到|回了|来到|走到|跑到|赶到|抵达|到了|进了|返回)(?<target>[^，。！？；：\n]{1,24})"""),
        Regex("""(?:带着|拉着|牵着)(?:你|我|他|她|大家)?[^，。！？；：\n]{0,12}(?:走进|进入|回到|来到|走到|到了)(?<target>[^，。！？；：\n]{1,24})"""),
        Regex("""(?:我们|我和你|你和我|两人|大家)(?:一起)?(?:走进|进入|回到|来到|走到|跑到|赶到|抵达)(?<target>[^，。！？；：\n]{1,24})"""),
    )

    private val PRESENCE_PATTERNS = listOf(
        Regex("""(?:我|我们|你|你们|他|她|他们|她们|两人|大家)?(?:已经|现在|此刻|这会儿|正)?(?:待在|留在|位于|身处|就在|正待在|正位于)(?<target>[^，。！？；：\n]{1,24})"""),
        Regex("""(?:坐|站|躺|靠|蹲|跪|趴|停|等|醒|睡|倚|立)(?:在|到了)(?<target>[^，。！？；：\n]{1,24})"""),
        Regex("""(?:在)(?<target>[^，。！？；：\n]{1,18})(?:坐下|坐着|站着|躺下|躺着|醒来|睡着|等着|停下|休息|碰面|见面)"""),
    )

    private val TIME_ADVANCE_PATTERNS = listOf(
        Regex("""第二天(?:清晨|早上|上午|中午|下午|傍晚|晚上|夜里)?"""),
        Regex("""次日(?:清晨|早上|上午|中午|下午|傍晚|晚上|夜里)?"""),
        Regex("""(?:几分钟|十几分钟|半小时|一小时|几小时|半天|一天|数日|几天)后"""),
        Regex("""(?:转眼|不知不觉|过了一会儿|片刻后|随后不久)"""),
        Regex("""(?:天亮时|天黑后|到了晚上|到了清晨|到了中午|到了傍晚)"""),
    )

    private val NARRATIVE_CUT = Regex(
        """(?:镜头|画面|场景)(?:一转|切到|来到)|(?:再睁眼|醒来时|等回过神|转眼间)""",
    )

    private val ABSTRACT_LOCATION_WORDS = listOf(
        "想",
        "考虑",
        "纠结",
        "意",
        "乎",
        "说",
        "问",
        "聊",
        "讨论",
        "等你",
        "等我",
        "等他",
        "等她",
    )
}


internal enum class ChatContinuityGuardMode(
    val actionPrefix: String,
    val failureMessage: String,
) {
    DIRECT("", "角色回复连续两次违反当前场景连续性，请重试本轮。"),
    GROUP("group-", "群聊角色回复连续两次违反当前场景连续性"),
    PROACTIVE("proactive-", "角色主动消息连续两次违反当前场景连续性"),
}

internal object ChatReplyContinuityGuard {
    suspend fun <T> enforce(
        previous: ChatSceneState,
        userMessage: String,
        initial: T,
        mode: ChatContinuityGuardMode,
        contentOf: (T) -> String,
        retry: suspend (repairHint: String) -> T,
        onEvent: (action: String, check: ChatContinuityCheck) -> Unit = { _, _ -> },
    ): T {
        val firstCheck = ChatSceneRuntime.inspectReply(
            previous = previous,
            userMessage = userMessage,
            assistantMessage = contentOf(initial),
        )
        if (firstCheck.accepted) return initial

        onEvent("${mode.actionPrefix}retry", firstCheck)
        val retried = try {
            retry(repairHint(previous, mode))
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the initial model response when an optional continuity repair fails.
            onEvent("${mode.actionPrefix}repair-failed-returned-original", firstCheck)
            return initial
        }
        val retryCheck = ChatSceneRuntime.inspectReply(
            previous = previous,
            userMessage = userMessage,
            assistantMessage = contentOf(retried),
        )
        if (!retryCheck.accepted) {
            onEvent("${mode.actionPrefix}accepted-with-warning", retryCheck)
            return if (contentOf(retried).isNotBlank()) retried else initial
        }

        onEvent("${mode.actionPrefix}repaired", retryCheck)
        return retried
    }

    private fun repairHint(
        scene: ChatSceneState,
        mode: ChatContinuityGuardMode,
    ): String = buildString {
        when (mode) {
            ChatContinuityGuardMode.DIRECT -> appendLine("【场景连续性修复】")
            ChatContinuityGuardMode.GROUP -> appendLine("【群聊场景连续性修复】")
            ChatContinuityGuardMode.PROACTIVE -> appendLine("【主动互动场景连续性修复】")
        }
        appendLine("上一版无过渡改变了场景。")
        if (scene.location.isNotBlank()) appendLine("当前地点：${scene.location}")
        if (scene.sceneTime.isNotBlank()) appendLine("当前时间：${scene.sceneTime}")
        appendLine("按当前场景重写；需要变化时补足自然过渡。")
        append("只输出最终回复。")
    }
}
