package com.labteto.dshmobile.local.profile

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserProfile(
    val customRules: String = "",
    val autoRecall: Boolean = true,
)

@Singleton
class UserProfileStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val file = File(context.filesDir, "local-harness/profile/user.json")

    @Synchronized
    fun read(): UserProfile {
        if (!file.isFile) return UserProfile()
        return runCatching {
            json.decodeFromString(UserProfile.serializer(), file.readText())
        }.getOrDefault(UserProfile())
    }

    @Synchronized
    fun write(profile: UserProfile) {
        val clean = profile.copy(customRules = profile.customRules.trim().take(MAX_RULE_CHARS))
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(UserProfile.serializer(), clean))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private companion object {
        const val MAX_RULE_CHARS = 6_000
    }
}
