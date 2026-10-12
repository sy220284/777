package com.labteto.dshmobile.ui.screens.local


import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryStory
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Design 07 identity card; artwork does not push the actual profile below the first screen. */
@Composable
internal fun FluidPersonaIdentity(entry: PersonaGalleryEntry, subtitle: String,
    enabled: Boolean, onPortrait: () -> Unit) {
    val colors = DsTheme.colors
    val portrait by produceState<ImageBitmap?>(null, entry.portraitPath) {
        value = withContext(Dispatchers.IO) { decodePersonaPortraitBitmap(entry.portraitPath, maxEdgePx = 320) }
    }
    SyGlassCard {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
            Surface(color = colors.characterAccentTertiary, shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(80.dp).clickable(enabled = enabled, onClick = onPortrait)) {
                val picture = portrait
                if (picture != null) Image(picture, entry.persona.name, contentScale = ContentScale.Fit)
                else Box(contentAlignment = Alignment.Center) {
                    Text(entry.persona.name.take(1), style = DsType.title22,
                        color = colors.characterAccent)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(entry.persona.name, style = DsType.title22.withReadingWeight(), color = colors.labelPrimary)
                Text(entry.persona.portrait.takeIf(String::isNotBlank) ?: subtitle,
                    style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
            }
        }
    }
}

/** All panels refer to the selected story, so a new story cannot inherit another timeline. */
@Composable
internal fun FluidPersonaStorySection(story: PersonaGalleryStory?, section: Int) {
    val colors = DsTheme.colors
    val state = story?.chatState
    SyGlassCard {
        when (section) {
            0 -> {
                FluidPersonaFactRow(stringResource(R.string.fluid_persona_stage), state?.relationshipState.orEmpty())
                FluidPersonaFactRow(stringResource(R.string.fluid_persona_now), state?.mood.orEmpty())
                FluidPersonaFactRow(stringResource(R.string.fluid_persona_unfinished),
                    state?.unresolvedThreads?.joinToString("\n").orEmpty())
                state?.currentFocus?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = DsType.std14.withReadingWeight(), color = colors.labelSecondary)
                }
            }
            1 -> {
                val events = (story?.chatContext?.continuity?.recentEvents.orEmpty() +
                    state?.dynamics?.sharedMoments.orEmpty()).filter(String::isNotBlank).distinct()
                if (events.isEmpty()) Text(stringResource(R.string.fluid_persona_no_events), color = colors.labelSecondary)
                events.forEachIndexed { index, event ->
                    Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        Text((index + 1).toString().padStart(2, '0'), style = DsType.caption11, color = colors.labelTertiary)
                        Text(event, style = DsType.std14.withReadingWeight(), color = colors.labelPrimary)
                    }
                }
            }
            2 -> {
                val memories = (state?.dynamics?.facts.orEmpty().map { it.text } +
                    state?.dynamics?.sharedObjects.orEmpty()).filter(String::isNotBlank).distinct()
                if (memories.isEmpty()) Text(stringResource(R.string.fluid_persona_no_memories), color = colors.labelSecondary)
                memories.forEach { Text(it, style = DsType.std14.withReadingWeight(), color = colors.labelPrimary) }
            }
        }
    }
}

@Composable
private fun FluidPersonaFactRow(label: String, value: String) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        Text(label, style = DsType.small13, color = colors.labelSecondary)
        Text(value.ifBlank { "—" }, modifier = Modifier.weight(1f),
            style = DsType.std14.withReadingWeight(), color = colors.labelPrimary)
    }
}
