package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.LocalMediaCatalog
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalMediaProvider
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalMediaVoiceLabelsTest {
    @Test fun officialVoiceIdsAreKeptButNamesAreChinese() {
        val profile = LocalModelProfile("mm", "MiniMax-M2.7", "https://api.minimaxi.com/v1", authKind = LocalModelAuthKind.API_KEY)
        val route = LocalMediaCatalog.offers(listOf(profile)).first { it.kind == LocalMediaKind.SPEECH }
        val voices = LocalMediaVoiceLabels.localize(route, listOf(
            LocalMediaVoice("female-shaonv", "female-shaonv"),
            LocalMediaVoice("unknown-provider-code", "English Unknown")
        ))
        assertEquals("female-shaonv", voices.first().id)
        assertEquals("少女", voices.first().name)
        assertFalse(voices[1].name.any(Char::isLetter).let { it && voices[1].name.contains("English") })
    }

    @Test fun unknownPersonaGenderIsNotInferred() {
        assertEquals("", LocalMediaVoiceLabels.suggestedId(LocalMediaProvider.MINIMAX, "喜欢吃甜点"))
        assertEquals("female-shaonv", LocalMediaVoiceLabels.suggestedId(LocalMediaProvider.MINIMAX, "女声清脆"))
    }
}
