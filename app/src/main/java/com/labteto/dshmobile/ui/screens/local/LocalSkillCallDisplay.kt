package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.labteto.dshmobile.local.presentation.localPresetSkillTitle

// A display-only projection: the persisted draft, copied source, and submitted prompt retain @skill:<id>.
private val selectedSkillMarker = Regex("^@skill:([a-z][a-z0-9-]{1,47})(?=\\r?\\n|$)")

private fun selectedSkillLabel(source: String): Pair<Int, String>? {
    val match = selectedSkillMarker.find(source) ?: return null
    val title = localPresetSkillTitle(match.groupValues[1]) ?: return null
    return match.value.length to "@$title"
}

internal fun localSkillCallDisplayText(source: String): String {
    val (markerLength, label) = selectedSkillLabel(source) ?: return source
    return label + source.substring(markerLength)
}

/** Replaces the invocation prefix visually while keeping editing offsets aligned with the raw marker. */
internal class LocalSkillCallVisualTransformation(private val accent: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val (markerLength, label) = selectedSkillLabel(text.text)
            ?: return TransformedText(text, OffsetMapping.Identity)
        val transformed = buildAnnotatedString {
            pushStyle(SpanStyle(color = accent))
            append(label)
            pop()
            append(text.subSequence(markerLength, text.length))
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = when {
                offset == 0 -> 0
                offset <= markerLength -> label.length
                else -> offset - markerLength + label.length
            }

            override fun transformedToOriginal(offset: Int): Int = when {
                offset == 0 -> 0
                offset <= label.length -> markerLength
                else -> offset - label.length + markerLength
            }
        }
        return TransformedText(transformed, mapping)
    }
}
