package com.labteto.dshmobile.local.profile

import com.labteto.dshmobile.local.persistence.RecoveringDocumentFile

import android.content.Context
import com.labteto.dshmobile.local.persistence.RecoveringDocumentFailurePolicy
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
    val autoMemory: Boolean = true,
)

@Singleton
class UserProfileStore internal constructor(
    private val file: File,
    private val json: Json,
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "local-harness/profile/user.json"), json)

    private val durableFile = RecoveringDocumentFile(
        file = file,
        failurePolicy = RecoveringDocumentFailurePolicy.RECREATE_DEFAULT,
    )

    @Synchronized
    fun read(): UserProfile =
        durableFile.read(
            defaultValue = ::UserProfile,
            decode = ::decode,
        )

    @Synchronized
    fun write(profile: UserProfile) {
        val clean = profile.copy(customRules = profile.customRules.trim().take(MAX_RULE_CHARS))
        val encoded = json.encodeToString(UserProfile.serializer(), clean)
        durableFile.write(encoded) { candidate ->
            runCatching { decode(candidate) }.isSuccess
        }
    }

    private fun decode(encoded: String): UserProfile =
        json.decodeFromString(UserProfile.serializer(), encoded)

    private companion object {
        const val MAX_RULE_CHARS = 6_000
    }
}
