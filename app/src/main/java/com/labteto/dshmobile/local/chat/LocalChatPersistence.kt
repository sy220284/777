package com.labteto.dshmobile.local.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Single process-wide owner for local chat persistence stores shared by Engine and Chat runtime. */
@Singleton
class LocalChatPersistence @Inject constructor(
    @ApplicationContext context: Context,
    personaStore: ChatPersonaStore,
    galleryStore: ChatPersonaGalleryStore,
    json: Json,
) {
    internal val personaStore = personaStore
    internal val galleryStore = galleryStore
    internal val diaryStore = ChatDiaryStore(File(context.filesDir, "local-harness/chat-diary"), json)
}
