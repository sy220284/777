package com.labteto.dshmobile.local.chat

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One-time compatibility boundary from the pre-V3 persona schema into the current "character life"
 * schema. Legacy fields never participate in the current runtime after this conversion.
 */
internal sealed interface PersonaDocumentImport {
    data class Archive(val entry: PersonaGalleryEntry) : PersonaDocumentImport
    data object Share : PersonaDocumentImport
}

internal object PersonaSchemaMigration {
    fun hasDurableSource(file: File): Boolean =
        file.isFile || File(file.parentFile, "${file.name}.bak").isFile

    fun readLegacyPersonaDocument(file: File, json: Json): LegacyPersonaDocumentV1 {
        val migrationJson = tolerant(json)
        return RecoveringChatDocumentFile(file).read(
            defaultValue = ::LegacyPersonaDocumentV1,
            decode = { encoded ->
                migrationJson.decodeFromString(LegacyPersonaDocumentV1.serializer(), encoded).also { document ->
                    require(document.version == 1) { "旧人物库版本不受支持" }
                }
            },
        )
    }

    fun readLegacyGalleryDocument(file: File, json: Json): LegacyGalleryDocumentV4 {
        val migrationJson = tolerant(json)
        return RecoveringChatDocumentFile(file).read(
            defaultValue = ::LegacyGalleryDocumentV4,
            decode = { encoded ->
                val root = migrationJson.parseToJsonElement(encoded).jsonObject
                when (val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1) {
                    4 -> migrationJson.decodeFromString(LegacyGalleryDocumentV4.serializer(), encoded)
                    1, 2, 3 -> migrationJson
                        .decodeFromString(LegacyGalleryDocumentV1To3.serializer(), encoded)
                        .toV4()
                    else -> error("旧人物图集版本不受支持：$version")
                }
            },
        )
    }

    fun decodeDocumentImport(json: Json, payload: String): PersonaDocumentImport {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { error -> throw IllegalArgumentException("人物迁移数据格式不正确", error) }
        val schema = root["schema"]?.jsonPrimitive?.intOrNull
        return when {
            schema == 3 && "entry" in root -> PersonaDocumentImport.Archive(
                PersonaTransferDocuments.decodeArchive(json, payload).entry,
            )
            schema == 2 && "entry" in root -> PersonaDocumentImport.Archive(
                decodeLegacyArchiveEntry(json, payload),
            )
            (schema == 2 || schema == 1) && "persona" in root -> PersonaDocumentImport.Share
            else -> throw IllegalArgumentException("人物文件版本不受支持")
        }
    }

    fun decodeShare(json: Json, payload: String): PersonaProfile {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { error -> throw IllegalArgumentException("人物分享数据格式不正确", error) }
        return when (root["schema"]?.jsonPrimitive?.intOrNull) {
            2 -> runCatching {
                json.decodeFromString(PersonaShareEnvelope.serializer(), payload).persona
            }.getOrElse { error -> throw IllegalArgumentException("人物分享数据格式不正确", error) }
            1 -> runCatching { decodeLegacyShare(json, payload) }
                .getOrElse { error -> throw IllegalArgumentException("旧人物分享数据无法升级", error) }
            else -> throw IllegalArgumentException("人物文件版本不受支持")
        }
    }

    fun decodeLegacyShare(json: Json, payload: String): PersonaProfile {
        val envelope = tolerant(json).decodeFromString(LegacyPersonaShareEnvelopeV1.serializer(), payload)
        require(envelope.schema == 1) { "旧人物分享版本不受支持" }
        return toCurrent(envelope.persona)
    }

    fun decodeLegacyArchiveEntry(json: Json, payload: String): PersonaGalleryEntry {
        val envelope = tolerant(json).decodeFromString(LegacyPersonaArchiveEnvelopeV2.serializer(), payload)
        require(envelope.schema == 2) { "旧人物档案版本不受支持" }
        return toCurrent(envelope.entry)
    }

    fun toCurrent(legacy: LegacyPersonaProfileV1): PersonaProfile {
        val mapped = PersonaProfile(
            id = legacy.id,
            name = legacy.name,
            portrait = joinText(
                legacy.identity,
                legacy.personality,
                legacy.speechStyle.takeIf(String::isNotBlank)?.let { "表达倾向：$it" }.orEmpty(),
            ),
            lifeContext = legacy.background,
            coreValues = mergeLines(legacy.valuePriorities, legacy.coreMotivations, 6),
            coreTension = legacy.internalContradictions
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("；")
                .take(2_000),
            quirks = legacy.behaviorPatterns
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .map { pattern -> "常见习惯：$pattern" }
                .take(12),
            initialUserImpression = legacy.relationship,
            voiceSamples = mergeLines(legacy.exampleDialogues, legacy.signaturePhrases, 20),
            worldSetting = legacy.worldSetting,
            franchise = legacy.franchise,
            timelinePosition = legacy.timelinePosition,
            knowledgeBoundary = legacy.knowledgeBoundary,
            loreEntries = legacy.loreEntries,
            presetId = legacy.presetId,
            hardConstraints = legacy.hardConstraints,
            bannedPhrases = legacy.bannedPhrases,
            corrections = legacy.corrections,
            behaviorTuning = legacy.behaviorTuning,
            updatedAt = legacy.updatedAt,
        )
        return enrichInstalledPreset(mapped)
    }

    fun toCurrent(legacy: LegacyPersonaGalleryEntryV4): PersonaGalleryEntry =
        PersonaGalleryEntry(
            id = legacy.id,
            persona = toCurrent(legacy.persona).copy(id = legacy.id),
            portraitPath = legacy.portraitPath,
            groupChatState = legacy.groupChatState,
            stories = legacy.stories,
            updatedAt = legacy.updatedAt,
        )

    /**
     * Current V3 values win. Legacy content only restores information that would otherwise be lost.
     */
    fun mergeCurrentFirst(current: PersonaProfile, migrated: PersonaProfile): PersonaProfile {
        val mergedLore = mergePersonaProfiles(current, migrated).loreEntries
        return current.copy(
            id = current.id.ifBlank { migrated.id },
            name = current.name
                .takeUnless { it.isBlank() || it == "默认角色" }
                ?: migrated.name,
            portrait = current.portrait.ifBlank { migrated.portrait },
            lifeContext = current.lifeContext.ifBlank { migrated.lifeContext },
            attentionBiases = mergeLines(current.attentionBiases, migrated.attentionBiases, 8),
            attentionKeywords = mergeLines(current.attentionKeywords, migrated.attentionKeywords, 12),
            perceptionBlindSpots = mergeLines(
                current.perceptionBlindSpots,
                migrated.perceptionBlindSpots,
                8,
            ),
            quirks = mergeLines(current.quirks, migrated.quirks, 12),
            limitations = mergeLines(current.limitations, migrated.limitations, 8),
            coreValues = mergeLines(current.coreValues, migrated.coreValues, 6),
            coreTension = current.coreTension.ifBlank { migrated.coreTension },
            stableTraits = mergeLines(current.stableTraits, migrated.stableTraits, 8),
            mutableTraits = mergeLines(current.mutableTraits, migrated.mutableTraits, 8),
            initialUserImpression = current.initialUserImpression.ifBlank { migrated.initialUserImpression },
            voiceSamples = mergeLines(current.voiceSamples, migrated.voiceSamples, 20),
            worldSetting = current.worldSetting.ifBlank { migrated.worldSetting },
            franchise = current.franchise.ifBlank { migrated.franchise },
            timelinePosition = current.timelinePosition.ifBlank { migrated.timelinePosition },
            knowledgeBoundary = mergeLines(current.knowledgeBoundary, migrated.knowledgeBoundary, 20),
            loreEntries = mergedLore,
            presetId = current.presetId.ifBlank { migrated.presetId },
            hardConstraints = mergeLines(current.hardConstraints, migrated.hardConstraints, 20),
            bannedPhrases = mergeLines(current.bannedPhrases, migrated.bannedPhrases, 30),
            corrections = mergeLines(current.corrections, migrated.corrections, 20),
            behaviorTuning = if (current.behaviorTuning == CharacterBehaviorTuning()) {
                migrated.behaviorTuning
            } else {
                current.behaviorTuning
            },
            updatedAt = maxOf(current.updatedAt, migrated.updatedAt),
        )
    }

    private fun enrichInstalledPreset(profile: PersonaProfile): PersonaProfile {
        val latest = PersonaPresetCatalog.presets
            .firstOrNull { preset -> preset.id == profile.presetId }
            ?.persona
            ?: return profile

        // Only fill V3-only dimensions. Existing semantic content remains the user's source of truth.
        val legacyPresetConstraints = setOf(
            "不读取玩家上帝视角，不凭空知道未发生或未获知的剧情。",
            "关系变化必须有共同经历支撑，不因几句对话直接跳级。",
            "不自称 AI，不讨论自己正在扮演角色。",
        )
        val legacyPresetMetaBans = setOf("作为AI", "根据设定我应该", "身为一个语言模型")
        return profile.copy(
            attentionBiases = latest.attentionBiases,
            attentionKeywords = latest.attentionKeywords,
            perceptionBlindSpots = latest.perceptionBlindSpots,
            quirks = mergeLines(profile.quirks, latest.quirks, 12),
            limitations = latest.limitations,
            mutableTraits = latest.mutableTraits,
            hardConstraints = profile.hardConstraints.filterNot { it in legacyPresetConstraints },
            bannedPhrases = profile.bannedPhrases.filterNot { it in legacyPresetMetaBans },
        )
    }

    private fun tolerant(json: Json): Json = Json(json) {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun joinText(vararg values: String): String =
        values.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("；")
            .take(4_000)

    private fun mergeLines(first: List<String>, second: List<String>, limit: Int): List<String> =
        (first + second)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(limit)
            .toList()
}

@Serializable
internal data class LegacyPersonaProfileV1(
    val id: String = PersonaProfile.DEFAULT_PERSONA_ID,
    val name: String = "默认角色",
    val identity: String = "",
    val background: String = "",
    val personality: String = "",
    val speechStyle: String = "",
    val relationship: String = "",
    val worldSetting: String = "",
    val franchise: String = "",
    val timelinePosition: String = "",
    val coreMotivations: List<String> = emptyList(),
    val valuePriorities: List<String> = emptyList(),
    val behaviorPatterns: List<String> = emptyList(),
    val internalContradictions: List<String> = emptyList(),
    val knowledgeBoundary: List<String> = emptyList(),
    val loreEntries: List<PersonaLoreEntry> = emptyList(),
    val presetId: String = "",
    val hardConstraints: List<String> = emptyList(),
    val exampleDialogues: List<String> = emptyList(),
    val bannedPhrases: List<String> = emptyList(),
    val signaturePhrases: List<String> = emptyList(),
    val corrections: List<String> = emptyList(),
    val behaviorTuning: CharacterBehaviorTuning = CharacterBehaviorTuning(),
    val updatedAt: Long = 0L,
)

@Serializable
internal data class LegacyPersonaDocumentV1(
    val version: Int = 1,
    val personas: List<LegacyPersonaProfileV1> = listOf(LegacyPersonaProfileV1()),
)

@Serializable
internal data class LegacyPersonaGalleryEntryV1To3(
    val id: String,
    val persona: LegacyPersonaProfileV1,
    val storyNotes: String = "",
    val history: List<com.labteto.dshmobile.local.LocalHarnessMessage> = emptyList(),
    val chatState: ChatCharacterState = ChatCharacterState(),
    val sourceSessionId: String = "",
    val updatedAt: Long = 0L,
)

@Serializable
internal data class LegacyGalleryDocumentV1To3(
    val version: Int = 1,
    val entries: List<LegacyPersonaGalleryEntryV1To3> = emptyList(),
) {
    fun toV4(): LegacyGalleryDocumentV4 = LegacyGalleryDocumentV4(
        version = 4,
        entries = entries.map { entry ->
            val hasStory = entry.storyNotes.isNotBlank() ||
                entry.history.isNotEmpty() ||
                entry.chatState.updatedAt > 0L ||
                entry.sourceSessionId.isNotBlank()
            LegacyPersonaGalleryEntryV4(
                id = entry.id,
                persona = entry.persona,
                groupChatState = ChatCharacterState(),
                stories = if (hasStory) {
                    listOf(
                        PersonaGalleryStory(
                            id = "legacy-story-${entry.id.take(80)}",
                            title = "旧故事",
                            notes = entry.storyNotes,
                            history = entry.history,
                            historyTotalCount = entry.history.count {
                                it.role == "user" || it.role == "assistant"
                            },
                            historyArchived = false,
                            chatState = entry.chatState,
                            sourceSessionIds = listOf(entry.sourceSessionId)
                                .filter(String::isNotBlank),
                            updatedAt = entry.updatedAt,
                        ),
                    )
                } else {
                    emptyList()
                },
                updatedAt = entry.updatedAt,
            )
        },
    )
}

@Serializable
internal data class LegacyPersonaGalleryEntryV4(
    val id: String,
    val persona: LegacyPersonaProfileV1,
    val portraitPath: String = "",
    val groupChatState: ChatCharacterState = ChatCharacterState(),
    val stories: List<PersonaGalleryStory> = emptyList(),
    val updatedAt: Long = 0L,
)

@Serializable
internal data class LegacyGalleryDocumentV4(
    val version: Int = 4,
    val entries: List<LegacyPersonaGalleryEntryV4> = emptyList(),
)

@Serializable
internal data class LegacyPersonaShareEnvelopeV1(
    val schema: Int = 1,
    val source: String = "神言神语",
    val persona: LegacyPersonaProfileV1,
)

@Serializable
internal data class LegacyPersonaArchiveEnvelopeV2(
    val schema: Int = 2,
    val source: String = "神言神语",
    val entry: LegacyPersonaGalleryEntryV4,
    val memorySummaries: List<PersonaTransferMemorySummary> = emptyList(),
)
