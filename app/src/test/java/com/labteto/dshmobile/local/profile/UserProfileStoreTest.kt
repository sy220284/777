package com.labteto.dshmobile.local.profile

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileStoreTest {
    private val root = Files.createTempDirectory("user-profile-store-").toFile()
    private val file = File(root, "profile/user.json")
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun writeSanitizesAndPersistsProfile() {
        val store = UserProfileStore(file, json)
        store.write(
            UserProfile(
                customRules = "  " + "规".repeat(6_100) + "  ",
                autoRecall = false,
                autoMemory = false,
            ),
        )

        val restored = UserProfileStore(file, json).read()

        assertEquals(6_000, restored.customRules.length)
        assertEquals(false, restored.autoRecall)
        assertEquals(false, restored.autoMemory)
    }

    @Test
    fun corruptPrimaryRecoversPreviousValidGeneration() {
        val store = UserProfileStore(file, json)
        store.write(UserProfile(customRules = "第一版规则", autoRecall = false))
        store.write(UserProfile(customRules = "第二版规则", autoMemory = false))

        file.writeText("{broken")

        val restored = UserProfileStore(file, json).read()

        assertEquals("第一版规则", restored.customRules)
        assertEquals(false, restored.autoRecall)
        assertEquals(true, restored.autoMemory)
        assertTrue(
            root.resolve("profile").listFiles().orEmpty().any {
                it.name.startsWith("user.json.corrupt-")
            },
        )
        assertEquals(restored, UserProfileStore(file, json).read())
    }

    @Test
    fun malformedProfileWithoutBackupIsQuarantinedInsteadOfOverwritten() {
        file.parentFile!!.mkdirs()
        file.writeText("{broken")

        val restored = UserProfileStore(file, json).read()

        assertEquals(UserProfile(), restored)
        assertTrue(
            file.parentFile!!.listFiles().orEmpty().any {
                it.name.startsWith("user.json.corrupt-")
            },
        )
    }
}
