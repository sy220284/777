package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.LocalHarnessMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A durable character profile plus its real story history. One character maps to one entry. */
@Serializable
data class PersonaGalleryEntry(
    val id: String,
    val persona: PersonaProfile,
    val storyNotes: String = "",
    val history: List<LocalHarnessMessage> = emptyList(),
    val chatState: ChatCharacterState = ChatCharacterState(),
    val sourceSessionId: String = "",
    val updatedAt: Long = 0L,
) {
    /** Only a small, clearly labelled excerpt is sent to the model; the archive keeps the full story. */
    fun storyContext(): String = buildString {
        if (storyNotes.isNotBlank()) appendLine("剧情提要：${storyNotes.trim().take(2_500)}")
        if (chatState.updatedAt > 0L) {
            appendLine("保存时的关系：${chatState.relationshipState.take(80)}")
            chatState.dynamics.sharedMoments.takeLast(6).takeIf { it.isNotEmpty() }?.let {
                appendLine("已发生的共同经历：${it.joinToString("；").take(800)}")
            }
            chatState.unresolvedThreads.takeLast(3).takeIf { it.isNotEmpty() }?.let {
                appendLine("仍待推进的线索：${it.joinToString("；").take(400)}")
            }
        }
        val excerpt = history.asReversed().asSequence()
            .filter { it.role == "user" || it.role == "assistant" }
            .map { "${if (it.role == "user") "用户" else persona.name}：${it.content.trim().take(600)}" }
            .take(16).toList().asReversed().joinToString("\n").takeLast(3_000)
        if (excerpt.isNotBlank()) appendLine("已保存故事的最近对话摘录：\n$excerpt")
    }.trim()
}

@Serializable
private data class GalleryDocument(
    val version: Int = 3,
    val entries: List<PersonaGalleryEntry> = emptyList(),
)

internal fun isMeaningfulGalleryPersona(persona: PersonaProfile): Boolean {
    val name = normalizePersonaText(persona.name)
    if (name.isBlank() || name in DEFAULT_PERSONA_NAMES) return false
    return persona.identity.isNotBlank() ||
        persona.background.isNotBlank() ||
        persona.personality.isNotBlank() ||
        persona.speechStyle.isNotBlank() ||
        persona.relationship.isNotBlank() ||
        persona.worldSetting.isNotBlank() ||
        persona.hardConstraints.isNotEmpty() ||
        persona.corrections.isNotEmpty()
}

internal fun samePersonaIdentity(left: PersonaProfile, right: PersonaProfile): Boolean {
    val leftName = normalizePersonaText(left.name)
    val rightName = normalizePersonaText(right.name)
    if (leftName.isBlank() || rightName.isBlank()) return false
    if (leftName in DEFAULT_PERSONA_NAMES || rightName in DEFAULT_PERSONA_NAMES) return false
    return leftName == rightName
}

internal fun mergePersonaProfiles(base: PersonaProfile, incoming: PersonaProfile): PersonaProfile =
    base.copy(
        name = chooseDisplayName(base.name, incoming.name),
        identity = mergePersonaText(base.identity, incoming.identity, 2_000),
        background = mergePersonaText(base.background, incoming.background, 4_000),
        personality = mergePersonaText(base.personality, incoming.personality, 2_000),
        speechStyle = mergePersonaText(base.speechStyle, incoming.speechStyle, 2_000),
        relationship = mergePersonaText(base.relationship, incoming.relationship, 2_000),
        worldSetting = mergePersonaText(base.worldSetting, incoming.worldSetting, 4_000),
        hardConstraints = mergePersonaLines(base.hardConstraints, incoming.hardConstraints, 20),
        exampleDialogues = mergePersonaLines(base.exampleDialogues, incoming.exampleDialogues, 12),
        bannedPhrases = mergePersonaLines(base.bannedPhrases, incoming.bannedPhrases, 30),
        signaturePhrases = mergePersonaLines(base.signaturePhrases, incoming.signaturePhrases, 20),
        corrections = mergePersonaLines(base.corrections, incoming.corrections, 20),
        updatedAt = maxOf(base.updatedAt, incoming.updatedAt),
    )

internal fun applyPersonaSuggestions(
    profile: PersonaProfile,
    suggestions: List<PersonaAppendSuggestion>,
): PersonaProfile {
    var result = profile
    suggestions.forEach { suggestion ->
        val value = suggestion.value.trim()
        if (value.isBlank()) return@forEach
        result = when (suggestion.field) {
            "identity" -> result.copy(identity = mergePersonaText(result.identity, value, 2_000))
            "background" -> result.copy(background = mergePersonaText(result.background, value, 4_000))
            "personality" -> result.copy(personality = mergePersonaText(result.personality, value, 2_000))
            "speechStyle" -> result.copy(speechStyle = mergePersonaText(result.speechStyle, value, 2_000))
            "relationship" -> result.copy(relationship = mergePersonaText(result.relationship, value, 2_000))
            "worldSetting" -> result.copy(worldSetting = mergePersonaText(result.worldSetting, value, 4_000))
            "hardConstraints" -> result.copy(hardConstraints = mergePersonaLines(result.hardConstraints, listOf(value), 20))
            "exampleDialogues" -> result.copy(exampleDialogues = mergePersonaLines(result.exampleDialogues, listOf(value), 12))
            "bannedPhrases" -> result.copy(bannedPhrases = mergePersonaLines(result.bannedPhrases, listOf(value), 30))
            "signaturePhrases" -> result.copy(signaturePhrases = mergePersonaLines(result.signaturePhrases, listOf(value), 20))
            "corrections" -> result.copy(corrections = mergePersonaLines(result.corrections, listOf(value), 20))
            else -> result
        }
    }
    return result
}

internal fun mergeGalleryEntries(
    base: PersonaGalleryEntry,
    incoming: PersonaGalleryEntry,
): PersonaGalleryEntry {
    val incomingIsNewer = incoming.chatState.updatedAt >= base.chatState.updatedAt
    val newer = if (incomingIsNewer) incoming.chatState else base.chatState
    val older = if (incomingIsNewer) base.chatState else incoming.chatState
    val mergedState = newer.copy(
        unresolvedThreads = mergePersonaLines(older.unresolvedThreads, newer.unresolvedThreads, 8),
        dynamics = newer.dynamics.copy(
            facts = mergeEvidence(older.dynamics.facts, newer.dynamics.facts, 24),
            hypotheses = mergeEvidence(older.dynamics.hypotheses, newer.dynamics.hypotheses, 16),
            unknowns = mergePersonaLines(older.dynamics.unknowns, newer.dynamics.unknowns, 12),
            sharedMoments = mergePersonaLines(older.dynamics.sharedMoments, newer.dynamics.sharedMoments, 30),
        ),
    )
    return base.copy(
        persona = mergePersonaProfiles(base.persona, incoming.persona).copy(id = base.id),
        storyNotes = mergePersonaText(base.storyNotes, incoming.storyNotes, 4_000),
        history = mergeHistory(base.history, incoming.history),
        chatState = mergedState,
        sourceSessionId = incoming.sourceSessionId.ifBlank { base.sourceSessionId },
        updatedAt = maxOf(base.updatedAt, incoming.updatedAt),
    )
}

internal fun compactDuplicateGalleryEntries(
    entries: List<PersonaGalleryEntry>,
): List<PersonaGalleryEntry> {
    if (entries.size < 2) return entries
    val compacted = mutableListOf<PersonaGalleryEntry>()
    entries.sortedByDescending { it.updatedAt }.forEach { entry ->
        val duplicateIndex = compacted.indexOfFirst { existing ->
            samePersonaIdentity(existing.persona, entry.persona)
        }
        if (duplicateIndex < 0) {
            compacted += entry
        } else {
            val canonical = compacted[duplicateIndex]
            compacted[duplicateIndex] = mergeGalleryEntries(canonical, entry).copy(
                id = canonical.id,
                persona = mergePersonaProfiles(canonical.persona, entry.persona).copy(id = canonical.id),
                sourceSessionId = canonical.sourceSessionId.ifBlank { entry.sourceSessionId },
                updatedAt = maxOf(canonical.updatedAt, entry.updatedAt),
            )
        }
    }
    return compacted.sortedByDescending { it.updatedAt }
}

internal fun galleryMessageArchiveKey(message: LocalHarnessMessage): String =
    message.id.takeIf(String::isNotBlank)
        ?: "${message.role}|${message.createdAt}|${normalizePersonaText(message.content)}"

internal fun removeArchivedGalleryMessage(
    entry: PersonaGalleryEntry,
    messageKey: String,
): PersonaGalleryEntry? {
    val remaining = entry.history.filterNot { galleryMessageArchiveKey(it) == messageKey }
    if (remaining.size == entry.history.size) return null
    return entry.copy(history = remaining)
}

private fun mergeEvidence(
    base: List<RelationshipEvidence>,
    incoming: List<RelationshipEvidence>,
    limit: Int,
): List<RelationshipEvidence> {
    val result = mutableListOf<RelationshipEvidence>()
    val index = linkedMapOf<String, Int>()
    (base + incoming).forEach { item ->
        val key = normalizePersonaText(item.text)
        if (key.isBlank()) return@forEach
        val previousIndex = index[key]
        if (previousIndex == null) {
            index[key] = result.size
            result += item
        } else if (item.confidence > result[previousIndex].confidence) {
            result[previousIndex] = item
        }
    }
    return result.takeLast(limit)
}

private fun mergeHistory(
    base: List<LocalHarnessMessage>,
    incoming: List<LocalHarnessMessage>,
): List<LocalHarnessMessage> {
    val seenIds = linkedSetOf<String>()
    val seenContent = linkedSetOf<String>()
    return (base + incoming)
        .asSequence()
        .filter { it.role == "user" || it.role == "assistant" }
        .filter { message ->
            val idUnique = message.id.isBlank() || seenIds.add(message.id)
            val contentKey = "${message.role}|${message.createdAt}|${normalizePersonaText(message.content)}"
            idUnique && seenContent.add(contentKey)
        }
        .sortedBy(LocalHarnessMessage::createdAt)
        .toList()
}

private fun chooseDisplayName(base: String, incoming: String): String {
    val current = base.trim()
    val fresh = incoming.trim()
    if (current.isBlank()) return fresh
    if (fresh.isBlank()) return current
    if (normalizePersonaText(current) in DEFAULT_PERSONA_NAMES &&
        normalizePersonaText(fresh) !in DEFAULT_PERSONA_NAMES
    ) return fresh
    return if (fresh.length > current.length && normalizePersonaText(fresh).contains(normalizePersonaText(current))) {
        fresh
    } else current
}

private fun mergePersonaText(base: String, incoming: String, limit: Int): String {
    val current = base.trim()
    val fresh = incoming.trim()
    if (current.isBlank()) return fresh.take(limit)
    if (fresh.isBlank()) return current.take(limit)

    val currentKey = normalizePersonaText(current)
    val freshKey = normalizePersonaText(fresh)
    if (currentKey == freshKey || currentKey.contains(freshKey)) return current.take(limit)
    if (freshKey.contains(currentKey)) return fresh.take(limit)

    val existingClauses = splitPersonaClauses(current)
    val known = existingClauses.mapTo(linkedSetOf(), ::normalizePersonaText)
    val additions = splitPersonaClauses(fresh).filter { known.add(normalizePersonaText(it)) }
    if (additions.isEmpty()) return current.take(limit)
    return (existingClauses + additions).joinToString("；").take(limit)
}

private fun mergePersonaLines(base: List<String>, incoming: List<String>, limit: Int): List<String> {
    val seen = linkedSetOf<String>()
    return (base + incoming)
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filter { seen.add(normalizePersonaText(it)) }
        .map { it.take(240) }
        .toList()
        .takeLast(limit)
}

private fun splitPersonaClauses(text: String): List<String> =
    text.split(Regex("""[；;。\n]+"""))
        .map(String::trim)
        .filter(String::isNotBlank)

private fun normalizePersonaText(text: String): String =
    text.lowercase()
        .replace("的", "")
        .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】—_-]+"""), "")

private val DEFAULT_PERSONA_NAMES = setOf(
    normalizePersonaText("默认角色"),
    normalizePersonaText("default"),
    normalizePersonaText("default角色"),
)

@Singleton
class ChatPersonaGalleryStore internal constructor(
    private val file: File,
    private val json: Json,
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "local-harness/chat/persona-gallery.json"), json)

    private val durableFile = RecoveringChatDocumentFile(file)

    @Synchronized
    fun list(): List<PersonaGalleryEntry> = readNormalized().entries.sortedByDescending { it.updatedAt }

    @Synchronized
    fun save(
        persona: PersonaProfile,
        sourceSessionId: String,
        history: List<LocalHarnessMessage>,
        chatState: ChatCharacterState,
        notes: String,
        existingId: String? = null,
    ): PersonaGalleryEntry {
        val doc = readNormalized()
        val explicit = existingId?.let { id -> doc.entries.firstOrNull { it.id == id } }
        val matched = explicit ?: doc.entries.firstOrNull { samePersonaIdentity(it.persona, persona) }
        val id = matched?.id ?: "gallery-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val incoming = PersonaGalleryEntry(
            id = id,
            persona = persona.copy(id = id),
            storyNotes = notes.trim().take(4_000),
            history = history.filter { it.role == "user" || it.role == "assistant" },
            chatState = chatState,
            sourceSessionId = sourceSessionId,
            updatedAt = now,
        )
        val entry = matched?.let { mergeGalleryEntries(it, incoming).copy(updatedAt = now) } ?: incoming
        write(doc.copy(version = 3, entries = doc.entries.filterNot { it.id == id } + entry))
        return entry
    }

    @Synchronized
    fun applySuggestions(id: String, suggestions: List<PersonaAppendSuggestion>): PersonaGalleryEntry? {
        if (suggestions.isEmpty()) return readNormalized().entries.firstOrNull { it.id == id }
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return null
        val now = System.currentTimeMillis()
        val merged = current.copy(
            persona = applyPersonaSuggestions(current.persona, suggestions)
                .copy(id = current.id, updatedAt = now),
            updatedAt = now,
        )
        write(doc.copy(version = 3, entries = doc.entries.map { if (it.id == id) merged else it }))
        return merged
    }

    @Synchronized
    fun updateNotes(id: String, notes: String): Boolean {
        val doc = readNormalized()
        if (doc.entries.none { it.id == id }) return false
        write(doc.copy(version = 3, entries = doc.entries.map {
            if (it.id == id) it.copy(storyNotes = notes.trim().take(4_000), updatedAt = System.currentTimeMillis()) else it
        }))
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val doc = readNormalized()
        if (doc.entries.none { it.id == id }) return false
        write(doc.copy(version = 3, entries = doc.entries.filterNot { it.id == id }))
        return true
    }

    @Synchronized
    fun deleteHistoryMessage(id: String, messageKey: String): Boolean {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return false
        val updated = removeArchivedGalleryMessage(current, messageKey)
            ?.copy(updatedAt = System.currentTimeMillis())
            ?: return false
        write(
            doc.copy(
                version = 3,
                entries = doc.entries.map { if (it.id == id) updated else it },
            ),
        )
        return true
    }

    private fun readNormalized(): GalleryDocument {
        val raw = read()
        val compacted = compactDuplicateGalleryEntries(raw.entries)
        val normalized = raw.copy(version = 3, entries = compacted)
        if (normalized != raw) write(normalized)
        return normalized
    }

    private fun read(): GalleryDocument =
        durableFile.read(
            defaultValue = ::GalleryDocument,
            decode = { encoded -> json.decodeFromString(GalleryDocument.serializer(), encoded) },
        )

    private fun write(doc: GalleryDocument) {
        val encoded = json.encodeToString(GalleryDocument.serializer(), doc)
        durableFile.write(encoded) { candidate ->
            runCatching {
                json.decodeFromString(GalleryDocument.serializer(), candidate)
            }.isSuccess
        }
    }
}
