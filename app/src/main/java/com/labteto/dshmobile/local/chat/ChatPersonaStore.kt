package com.labteto.dshmobile.local.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersonaProfile(
    val id: String = DEFAULT_PERSONA_ID,
    val name: String = "默认角色",
    val identity: String = "",
    val background: String = "",
    val personality: String = "",
    val speechStyle: String = "",
    val relationship: String = "",
    val worldSetting: String = "",
    val hardConstraints: List<String> = emptyList(),
    val exampleDialogues: List<String> = emptyList(),
    val bannedPhrases: List<String> = emptyList(),
    val signaturePhrases: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_PERSONA_ID = "default"
    }
}

@Serializable
private data class PersonaDocument(
    val version: Int = 1,
    val personas: List<PersonaProfile> = listOf(PersonaProfile()),
)

@Singleton
class ChatPersonaStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val file = File(context.filesDir, "local-harness/chat/personas.json")

    @Synchronized
    fun list(): List<PersonaProfile> = read().personas.sortedByDescending(PersonaProfile::updatedAt)

    @Synchronized
    fun get(id: String): PersonaProfile =
        read().personas.firstOrNull { it.id == id } ?: PersonaProfile()

    @Synchronized
    fun upsert(profile: PersonaProfile): PersonaProfile {
        val clean = sanitize(profile).copy(updatedAt = System.currentTimeMillis())
        val document = read()
        val personas = document.personas.filterNot { it.id == clean.id } + clean
        write(document.copy(personas = personas))
        return clean
    }

    private fun sanitize(profile: PersonaProfile): PersonaProfile = profile.copy(
        id = profile.id.trim().take(80).ifBlank { PersonaProfile.DEFAULT_PERSONA_ID },
        name = profile.name.trim().take(80).ifBlank { "默认角色" },
        identity = profile.identity.trim().take(MAX_FIELD_CHARS),
        background = profile.background.trim().take(MAX_LONG_FIELD_CHARS),
        personality = profile.personality.trim().take(MAX_FIELD_CHARS),
        speechStyle = profile.speechStyle.trim().take(MAX_FIELD_CHARS),
        relationship = profile.relationship.trim().take(MAX_FIELD_CHARS),
        worldSetting = profile.worldSetting.trim().take(MAX_LONG_FIELD_CHARS),
        hardConstraints = cleanLines(profile.hardConstraints, 20),
        exampleDialogues = cleanLines(profile.exampleDialogues, 12),
        bannedPhrases = cleanLines(profile.bannedPhrases, 30),
        signaturePhrases = cleanLines(profile.signaturePhrases, 20),
    )

    private fun cleanLines(values: List<String>, limit: Int): List<String> =
        values.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { it.take(240) }
            .distinct()
            .take(limit)
            .toList()

    private fun read(): PersonaDocument {
        if (!file.isFile) return PersonaDocument()
        return runCatching {
            json.decodeFromString(PersonaDocument.serializer(), file.readText())
        }.getOrDefault(PersonaDocument())
    }

    private fun write(document: PersonaDocument) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(PersonaDocument.serializer(), document))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private companion object {
        const val MAX_FIELD_CHARS = 2_000
        const val MAX_LONG_FIELD_CHARS = 4_000
    }
}
